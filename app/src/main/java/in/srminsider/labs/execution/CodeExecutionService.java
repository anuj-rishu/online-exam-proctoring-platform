package in.srminsider.labs.execution;

import in.srminsider.labs.domain.model.TestCase;
import lombok.extern.slf4j.Slf4j;
import in.srminsider.labs.config.CpuBudget;
import in.srminsider.labs.config.MemoryBudget;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeExecutionService {

    @Value("${code.execution.sandbox.enabled:true}")
    private boolean sandboxEnabled;

    private final MemoryBudget memoryBudget;

    @Value("${code.execution.sandbox.pids-limit:64}")
    private int sandboxPidsLimit;

    /**
     * Per-container CPU cap, derived from the machine's core count and the
     * configured budget rather than fixed, so this scales with the host.
     */
    private final CpuBudget cpuBudget;

    @Value("${code.execution.sandbox.timeout:5}")
    private int sandboxTimeout;

    
    private static final String PYTHON_IMAGE = "sandbox-python";
    private static final String NODE_IMAGE   = "sandbox-node";
    private static final String JAVA_IMAGE   = "sandbox-java";

    public Map<String, Object> execute(String language, String code) {
        return execute(language, code, null);
    }

    public Map<String, Object> execute(String language, String code, String stdin) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("code-exec-");

            if ("python".equalsIgnoreCase(language)) {
                return executePython(tempDir, code, stdin);
            } else if ("java".equalsIgnoreCase(language)) {
                return executeJava(tempDir, code, stdin);
            } else if ("javascript".equalsIgnoreCase(language) || "js".equalsIgnoreCase(language)) {
                return executeJavascript(tempDir, code, stdin);
            } else {
                return Map.of("stdout", "", "stderr", "Unsupported language", "exitCode", -1);
            }
        } catch (Exception e) {
            log.error("Execution error", e);
            return Map.of("stdout", "", "stderr", e.getMessage(), "exitCode", -1);
        } finally {
            if (tempDir != null) {
                deleteDirectory(tempDir.toFile());
            }
        }
    }

    
    // Rounds rather than floors: flooring only ever cost the student marks, and
    // partial work should land on the nearest mark, not the one below it.
    static int scoreFor(int passed, int total, int maxMarks) {
        if (total <= 0 || maxMarks <= 0 || passed <= 0) return 0;
        if (passed >= total) return maxMarks;
        return (int) Math.round((double) passed / total * maxMarks);
    }

    public Map<String, Object> executeTestCases(String language, String code, List<TestCase> testCases, int maxMarks) {
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        int total = testCases != null ? testCases.size() : 0;

        if (testCases != null) {
            for (int i = 0; i < testCases.size(); i++) {
                TestCase tc = testCases.get(i);
                Map<String, Object> execResult = execute(language, code, tc.getInput());

                String actualOutput = ((String) execResult.getOrDefault("stdout", "")).trim();
                String expectedOutput = tc.getExpectedOutput() != null ? tc.getExpectedOutput().trim() : "";
                int exitCode = (int) execResult.getOrDefault("exitCode", -1);

                boolean isPassed = exitCode == 0 && normalizeOutput(actualOutput).equals(normalizeOutput(expectedOutput));
                if (isPassed) passed++;

                Map<String, Object> tcResult = new LinkedHashMap<>();
                tcResult.put("testCaseIndex", i + 1);
                tcResult.put("passed", isPassed);
                tcResult.put("sample", tc.isSample());

                if (tc.isSample()) {
                    tcResult.put("input", tc.getInput());
                    tcResult.put("expectedOutput", tc.getExpectedOutput());
                    tcResult.put("actualOutput", actualOutput);
                    tcResult.put("explanation", tc.getExplanation());
                } else {
                    tcResult.put("input", null);
                    tcResult.put("expectedOutput", null);
                    tcResult.put("actualOutput", null);
                }

                String stderr = (String) execResult.getOrDefault("stderr", "");
                if (!stderr.isEmpty()) {
                    tcResult.put("stderr", tc.isSample() ? stderr : "Runtime error");
                }

                results.add(tcResult);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalTestCases", total);
        response.put("passedTestCases", passed);
        response.put("failedTestCases", total - passed);
        response.put("score", scoreFor(passed, total, maxMarks));
        response.put("maxScore", maxMarks);
        response.put("testResults", results);

        return response;
    }

    public Map<String, Object> executeTestCasesAdmin(String language, String code, List<TestCase> testCases, int maxMarks) {
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        int total = testCases != null ? testCases.size() : 0;

        if (testCases != null) {
            for (int i = 0; i < testCases.size(); i++) {
                TestCase tc = testCases.get(i);
                Map<String, Object> execResult = execute(language, code, tc.getInput());

                String actualOutput = ((String) execResult.getOrDefault("stdout", "")).trim();
                String expectedOutput = tc.getExpectedOutput() != null ? tc.getExpectedOutput().trim() : "";
                int exitCode = (int) execResult.getOrDefault("exitCode", -1);
                String stderr = (String) execResult.getOrDefault("stderr", "");

                boolean isPassed = exitCode == 0 && normalizeOutput(actualOutput).equals(normalizeOutput(expectedOutput));
                if (isPassed) passed++;

                Map<String, Object> tcResult = new LinkedHashMap<>();
                tcResult.put("testCaseIndex", i + 1);
                tcResult.put("passed", isPassed);
                tcResult.put("sample", tc.isSample());
                tcResult.put("input", tc.getInput());
                tcResult.put("expectedOutput", tc.getExpectedOutput());
                tcResult.put("actualOutput", actualOutput);
                if (!stderr.isEmpty()) {
                    tcResult.put("stderr", stderr);
                }
                results.add(tcResult);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalTestCases", total);
        response.put("passedTestCases", passed);
        response.put("failedTestCases", total - passed);
        response.put("score", scoreFor(passed, total, maxMarks));
        response.put("maxScore", maxMarks);
        response.put("testResults", results);

        return response;
    }

    private String normalizeOutput(String output) {
        if (output == null) return "";
        return output.replaceAll("\\r\\n", "\n")
                     .replaceAll("\\r", "\n")
                     .trim()
                     .replaceAll("\\s+$", "")
                     .replaceAll("(?m)^\\s+$", "")
                     .replaceAll("\\n+$", "");
    }

    private Map<String, Object> executePython(Path tempDir, String code, String stdin) throws Exception {
        Path scriptFile = tempDir.resolve("script.py");
        Files.writeString(scriptFile, code);
        if (sandboxEnabled) {
            return runDockerProcess(tempDir, PYTHON_IMAGE, stdin, "python3", "/sandbox/script.py");
        }
        return runProcess(tempDir.toFile(), stdin, "python3", "script.py");
    }

    private Map<String, Object> executeJavascript(Path tempDir, String code, String stdin) throws Exception {
        Path scriptFile = tempDir.resolve("script.js");
        Files.writeString(scriptFile, code);
        if (sandboxEnabled) {
            return runDockerProcess(tempDir, NODE_IMAGE, stdin, "node", "/sandbox/script.js");
        }
        return runProcess(tempDir.toFile(), stdin, "node", "script.js");
    }

    private Map<String, Object> executeJava(Path tempDir, String code, String stdin) throws Exception {
        Path scriptFile = tempDir.resolve("Main.java");
        Files.writeString(scriptFile, code);
        if (sandboxEnabled) {
            
            Map<String, Object> compileResult = runDockerProcess(tempDir, JAVA_IMAGE, null, "javac", "/sandbox/Main.java");
            if ((int) compileResult.get("exitCode") != 0) {
                return compileResult;
            }
            
            return runDockerProcess(tempDir, JAVA_IMAGE, stdin, "java", "-cp", "/sandbox", "Main");
        }
        Map<String, Object> compileResult = runProcess(tempDir.toFile(), null, "javac", "Main.java");
        if ((int) compileResult.get("exitCode") != 0) {
            return compileResult;
        }
        return runProcess(tempDir.toFile(), stdin, "java", "Main");
    }

    
    private Map<String, Object> runDockerProcess(Path tempDir, String image, String stdin, String... command) throws Exception {
        
        tempDir.toFile().setReadable(true, false);
        tempDir.toFile().setExecutable(true, false);
        for (File f : Objects.requireNonNull(tempDir.toFile().listFiles())) {
            f.setReadable(true, false);
        }

        List<String> dockerCmd = new ArrayList<>();
        dockerCmd.add("docker");
        dockerCmd.add("run");
        dockerCmd.add("--rm");
        dockerCmd.add("-i"); // required, else container stdin is never attached
        dockerCmd.add("--network=none");
        dockerCmd.add("--memory=" + memoryBudget.getMemoryPerContainer());  
        dockerCmd.add("--pids-limit=" + sandboxPidsLimit); 
        dockerCmd.add("--cpus=" + cpuBudget.dockerCpusArg());      
        dockerCmd.add("--user=65534:65534");          
        dockerCmd.add("--cap-drop=ALL");              
        dockerCmd.add("--security-opt=no-new-privileges"); 
        dockerCmd.add("--read-only");                 
        dockerCmd.add("--tmpfs");
        dockerCmd.add("/tmp:rw,noexec,nosuid,size=64m"); 
        dockerCmd.add("-v");
        dockerCmd.add(tempDir.toAbsolutePath() + ":/sandbox"); 
        dockerCmd.add("-w");
        dockerCmd.add("/sandbox");
        dockerCmd.add(image);                         
        dockerCmd.addAll(Arrays.asList(command));      

        ProcessBuilder pb = new ProcessBuilder(dockerCmd);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        try (OutputStream os = process.getOutputStream()) {
            if (stdin != null && !stdin.isEmpty()) {
                os.write(stdin.getBytes());
                os.flush();
            }
        } catch (IOException ignored) {
        }

        boolean finished = process.waitFor(sandboxTimeout + 2, TimeUnit.SECONDS); 
        if (!finished) {
            process.destroyForcibly();
            return Map.of("stdout", "", "stderr", "Timeout Exceeded (" + sandboxTimeout + " seconds).", "exitCode", 124);
        }

        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());

        return Map.of("stdout", stdout, "stderr", stderr, "exitCode", process.exitValue());
    }

    
    private Map<String, Object> runProcess(File workingDir, String stdin, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        try (OutputStream os = process.getOutputStream()) {
            if (stdin != null && !stdin.isEmpty()) {
                os.write(stdin.getBytes());
                os.flush();
            }
        } catch (IOException ignored) {
        }

        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return Map.of("stdout", "", "stderr", "Timeout Exceeded (5 seconds).", "exitCode", 124);
        }

        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());

        return Map.of("stdout", stdout, "stderr", stderr, "exitCode", process.exitValue());
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
}
