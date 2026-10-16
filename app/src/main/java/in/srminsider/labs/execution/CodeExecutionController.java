package in.srminsider.labs.execution;

import in.srminsider.labs.domain.model.Problem;
import in.srminsider.labs.domain.repository.ProblemRepository;
import in.srminsider.labs.execution.dto.ExecutionRequests;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/execute")
@RequiredArgsConstructor
public class CodeExecutionController {

    private final CodeExecutionQueueService codeExecutionService;
    private final ProblemRepository problemRepository;
    private final RedissonClient redisson;

    
    private static final int MAX_EXECUTIONS_PER_MINUTE = 10;

    
    private final ConcurrentHashMap<String, RRateLimiter> rateLimiters = new ConcurrentHashMap<>();

    
    private boolean acquireRateLimit(String username) {
        RRateLimiter limiter = rateLimiters.computeIfAbsent(
                "rate:exec:" + username,
                key -> {
                    RRateLimiter rl = redisson.getRateLimiter(key);
                    rl.trySetRate(RateType.OVERALL, MAX_EXECUTIONS_PER_MINUTE, 1, RateIntervalUnit.MINUTES);
                    return rl;
                });
        return limiter.tryAcquire(1);
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping
    public ResponseEntity<?> executeCode(@Valid @RequestBody ExecutionRequests.RunCode request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        if (!acquireRateLimit(username)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded — max " + MAX_EXECUTIONS_PER_MINUTE + " executions per minute. Please wait."));
        }

        Map<String, Object> result = codeExecutionService.execute(request.language(), request.code(), request.stdin());
        return ResponseEntity.ok(result);
    }

    @PreAuthorize("hasRole('STUDENT')")
    @PostMapping("/test-cases")
    public ResponseEntity<?> runTestCases(@Valid @RequestBody ExecutionRequests.RunTestCases request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        if (!acquireRateLimit(username)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded — max " + MAX_EXECUTIONS_PER_MINUTE + " executions per minute. Please wait."));
        }

        Optional<Problem> pOpt = problemRepository.findById(request.problemId());
        if (pOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Problem not found"));
        }

        Problem problem = pOpt.get();
        if (problem.getTestCases() == null || problem.getTestCases().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No test cases available for this problem"));
        }

        int maxMarks = problem.getMarks() != null ? problem.getMarks() : 5;
        Map<String, Object> result = codeExecutionService.executeTestCases(
                request.language(), request.code(), problem.getTestCases(), maxMarks);
        return ResponseEntity.ok(result);
    }
}
