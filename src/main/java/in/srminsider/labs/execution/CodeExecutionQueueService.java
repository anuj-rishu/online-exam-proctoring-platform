package in.srminsider.labs.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.srminsider.labs.config.CpuBudget;
import in.srminsider.labs.domain.model.TestCase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class CodeExecutionQueueService {

    private static final String QUEUE_KEY = "code-exec:queue";
    private static final Duration JOB_TTL = Duration.ofMinutes(5);

    private final RedissonClient redisson;
    private final CodeExecutionService codeExecutionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CpuBudget cpuBudget;
    private int workerPoolSize;

    @Value("${code.execution.job.timeout-seconds:25}")
    private int jobTimeoutSeconds;

    private ExecutorService workerExecutor;
    private volatile boolean running = true;

    public CodeExecutionQueueService(RedissonClient redisson, CodeExecutionService codeExecutionService,
                                     CpuBudget cpuBudget) {
        this.redisson = redisson;
        this.codeExecutionService = codeExecutionService;
        this.cpuBudget = cpuBudget;
    }

    @PostConstruct
    public void startWorkers() {
        workerPoolSize = cpuBudget.getWorkerPoolSize();
        workerExecutor = Executors.newFixedThreadPool(workerPoolSize, r -> {
            Thread t = new Thread(r, "code-exec-worker");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < workerPoolSize; i++) {
            workerExecutor.submit(this::workerLoop);
        }
        log.info("[CodeExecQueue] Started {} worker threads polling '{}'", workerPoolSize, QUEUE_KEY);
    }

    @PreDestroy
    public void stopWorkers() {
        running = false;
        if (workerExecutor != null) {
            workerExecutor.shutdownNow();
        }
    }

    private void workerLoop() {
        RBlockingQueue<String> queue = redisson.getBlockingQueue(QUEUE_KEY);
        while (running) {
            try {
                String jobId = queue.poll(5, TimeUnit.SECONDS);
                if (jobId == null) continue;
                processJob(jobId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[CodeExecQueue] Worker loop error", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void processJob(String jobId) {
        RBucket<String> requestBucket = redisson.getBucket("code-exec:req:" + jobId);
        String reqJson = requestBucket.get();
        requestBucket.delete();

        if (reqJson == null) {
            log.warn("[CodeExecQueue] Job {} had no request payload (expired before a worker picked it up)", jobId);
            return;
        }

        Map<String, Object> resultPayload;
        try {
            JobRequest req = objectMapper.readValue(reqJson, JobRequest.class);
            Map<String, Object> result = switch (req.mode) {
                case "testcases" -> codeExecutionService.executeTestCases(req.language, req.code, req.testCases, req.maxMarks);
                case "testcases-admin" -> codeExecutionService.executeTestCasesAdmin(req.language, req.code, req.testCases, req.maxMarks);
                default -> codeExecutionService.execute(req.language, req.code, req.stdin);
            };
            resultPayload = Map.of("status", "done", "result", result);
        } catch (Exception e) {
            log.error("[CodeExecQueue] Job {} failed", jobId, e);
            resultPayload = Map.of("status", "error", "error", String.valueOf(e.getMessage()));
        }

        try {
            String resultJson = objectMapper.writeValueAsString(resultPayload);
            redisson.getTopic("code-exec:done:" + jobId).publish(resultJson);
        } catch (Exception e) {
            log.error("[CodeExecQueue] Failed to publish result for job {}", jobId, e);
        }
    }

    public Map<String, Object> execute(String language, String code, String stdin) {
        return submitAndWait(new JobRequest("simple", language, code, stdin, null, 0));
    }

    public Map<String, Object> executeTestCases(String language, String code, List<TestCase> testCases, int maxMarks) {
        return submitAndWait(new JobRequest("testcases", language, code, null, testCases, maxMarks));
    }

    public Map<String, Object> executeTestCasesAdmin(String language, String code, List<TestCase> testCases, int maxMarks) {
        return submitAndWait(new JobRequest("testcases-admin", language, code, null, testCases, maxMarks));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> submitAndWait(JobRequest req) {
        String jobId = UUID.randomUUID().toString();

        try {
            redisson.getBucket("code-exec:req:" + jobId).set(objectMapper.writeValueAsString(req), JOB_TTL);
        } catch (Exception e) {
            log.error("[CodeExecQueue] Failed to enqueue job", e);
            return Map.of("stdout", "", "stderr", "Failed to queue execution", "exitCode", -1);
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        RTopic topic = redisson.getTopic("code-exec:done:" + jobId);
        int listenerId = topic.addListener(String.class, (channel, msg) -> future.complete(msg));

        try {
            redisson.getBlockingQueue(QUEUE_KEY).add(jobId);

            String resultJson = future.get(jobTimeoutSeconds, TimeUnit.SECONDS);
            Map<String, Object> payload = objectMapper.readValue(resultJson, Map.class);

            if ("done".equals(payload.get("status"))) {
                return (Map<String, Object>) payload.get("result");
            }
            return Map.of("stdout", "", "stderr", String.valueOf(payload.getOrDefault("error", "Execution failed")), "exitCode", -1);
        } catch (TimeoutException e) {
            log.warn("[CodeExecQueue] Job {} timed out waiting for a free worker", jobId);
            return Map.of("stdout", "", "stderr", "Server is busy — execution queue timed out. Please try again.", "exitCode", -1);
        } catch (Exception e) {
            log.error("[CodeExecQueue] submitAndWait failed for job {}", jobId, e);
            return Map.of("stdout", "", "stderr", "Execution failed: " + e.getMessage(), "exitCode", -1);
        } finally {
            topic.removeListener(listenerId);
        }
    }

    public Map<String, Object> getStats() {
        return Map.of(
                "queuedJobs", redisson.getBlockingQueue(QUEUE_KEY).size(),
                "workerPoolSize", workerPoolSize
        );
    }

    public static class JobRequest {
        public String mode;
        public String language;
        public String code;
        public String stdin;
        public List<TestCase> testCases;
        public int maxMarks;

        public JobRequest() {}

        public JobRequest(String mode, String language, String code, String stdin, List<TestCase> testCases, int maxMarks) {
            this.mode = mode;
            this.language = language;
            this.code = code;
            this.stdin = stdin;
            this.testCases = testCases;
            this.maxMarks = maxMarks;
        }
    }
}
