package in.srminsider.labs.config;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


@Slf4j
@Component
public class PythonServiceLauncher {

    @Value("${identity.verification.enabled:true}")
    private boolean enabled;

    @Value("${identity.verification.port:8500}")
    private int port;

    @Value("${identity.verification.service-dir:python-services/identity-verification}")
    private String serviceDirProperty;

    private Process process;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("[PythonService] Identity verification service disabled via config");
            return;
        }

        Path serviceDir = resolveServiceDir();
        if (serviceDir == null) {
            return;
        }

        Path pythonBin = pythonExecutable(serviceDir);
        if (!Files.isExecutable(pythonBin)) {
            log.warn("[PythonService] No virtualenv found at {} — identity verification will be unavailable. "
                    + "Run 'python-services/identity-verification/setup.sh' once, then restart.", pythonBin);
            return;
        }

        try {
            Path logFile = serviceDir.resolve("uvicorn.log");
            ProcessBuilder pb = new ProcessBuilder(
                    pythonBin.toString(), "-m", "uvicorn", "app:app",
                    "--host", "127.0.0.1", "--port", String.valueOf(port))
                    .directory(serviceDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

            process = pb.start();
            log.info("[PythonService] Identity verification service starting on port {} (pid {}), logging to {}",
                    port, process.pid(), logFile);
        } catch (IOException e) {
            log.error("[PythonService] Failed to start identity verification service", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (process == null || !process.isAlive()) {
            return;
        }
        log.info("[PythonService] Stopping identity verification service (pid {})", process.pid());
        process.destroy();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private Path resolveServiceDir() {
        Path configured = Paths.get(serviceDirProperty);
        Path resolved = configured.isAbsolute()
                ? configured
                : Paths.get(System.getProperty("user.dir")).resolve(configured);

        if (!Files.isDirectory(resolved)) {
            log.warn("[PythonService] Service directory not found at {} — identity verification will be unavailable",
                    resolved);
            return null;
        }
        return resolved;
    }

    private Path pythonExecutable(Path serviceDir) {
        String binName = File.separatorChar == '\\' ? "python.exe" : "python";
        String venvSubdir = File.separatorChar == '\\' ? "Scripts" : "bin";
        return serviceDir.resolve(".venv").resolve(venvSubdir).resolve(binName);
    }
}
