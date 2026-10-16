package in.srminsider.labs.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Works out how much memory one sandbox container may use, sized from the
 * machine rather than a fixed number — the same reasoning as {@link CpuBudget}.
 *
 * The share is taken out of what is left after the platform's own needs, not
 * out of total RAM. This JVM, MongoDB, Redis and the OS all live on the same
 * host, and handing the difference to student containers would have the kernel
 * killing MongoDB mid-exam.
 */
@Slf4j
@Component
public class MemoryBudget {

    private static final long MB = 1024L * 1024L;

    /** Share of the machine's memory the platform as a whole may consume. */
    @Value("${code.execution.memory-utilisation:0.8}")
    private double utilisation;

    /** Explicit per-container limit such as "512m"; blank means derive it. */
    @Value("${code.execution.sandbox.memory:}")
    private String configuredMemory;

    private final CpuBudget cpuBudget;

    @Getter
    private long totalSystemMb;

    /** Docker {@code --memory} value for one submission's container. */
    @Getter
    private String memoryPerContainer;

    public MemoryBudget(CpuBudget cpuBudget) {
        this.cpuBudget = cpuBudget;
    }

    @PostConstruct
    void calculate() {
        if (configuredMemory != null && !configuredMemory.isBlank()) {
            memoryPerContainer = configuredMemory.trim();
            log.info("[MemoryBudget] using configured sandbox memory {}", memoryPerContainer);
            return;
        }

        totalSystemMb = detectTotalMemoryMb();
        long jvmMaxMb = Runtime.getRuntime().maxMemory() / MB;

        double clamped = Math.max(0.1, Math.min(1.0, utilisation));
        long budgetMb = (long) (totalSystemMb * clamped);

        // MongoDB's cache, Redis and the OS sit outside this JVM and are
        // invisible to it, so a quarter of the machine is held back for them.
        long platformReserveMb = Math.max(1024L, totalSystemMb / 4);
        long sandboxBudgetMb = budgetMb - jvmMaxMb - platformReserveMb;

        int workers = Math.max(1, cpuBudget.getWorkerPoolSize());
        long perContainerMb = sandboxBudgetMb / workers;

        // 256MB is the floor a JVM needs to start inside the Java sandbox;
        // beyond 1GB a single student submission gains nothing.
        perContainerMb = Math.max(256L, Math.min(1024L, perContainerMb));
        memoryPerContainer = perContainerMb + "m";

        if (sandboxBudgetMb < 256L * workers) {
            log.warn("[MemoryBudget] only {}MB spare for {} workers; using {} each, "
                            + "which may overcommit this machine",
                    Math.max(0, sandboxBudgetMb), workers, memoryPerContainer);
        }

        log.info("[MemoryBudget] {}MB system, {}% budget, {}MB JVM heap + {}MB reserved "
                        + "-> {} x {} sandboxes ({}MB max for student code)",
                totalSystemMb, Math.round(clamped * 100), jvmMaxMb, platformReserveMb,
                workers, memoryPerContainer, perContainerMb * workers);
    }

    private long detectTotalMemoryMb() {
        try {
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                return sunOs.getTotalMemorySize() / MB;
            }
        } catch (Throwable ignored) {
            // Fall through to the conservative default below.
        }
        log.warn("[MemoryBudget] could not read system memory; assuming 4GB");
        return 4096L;
    }
}
