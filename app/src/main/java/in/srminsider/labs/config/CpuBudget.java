package in.srminsider.labs.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Works out how much of the machine student code execution may use.
 *
 * Sized from the cores actually available rather than fixed numbers, so the
 * same build behaves sensibly on a laptop and on a larger server without
 * anyone remembering to retune it.
 *
 * The share is deliberately not 100%. This process also relays live
 * proctoring video, serves the exam, and shares the host with MongoDB, Redis
 * and the Docker daemon — saturating every core makes the proctor's feed
 * stutter and can stall WebSocket traffic, which matters more during an exam
 * than finishing a code run a second sooner.
 */
@Slf4j
@Component
public class CpuBudget {

    /** Share of the machine's cores student code execution may consume. */
    @Value("${code.execution.cpu-utilisation:0.8}")
    private double utilisation;

    /** Explicit worker count; 0 means derive it from the CPU share. */
    @Value("${code.execution.worker.pool-size:0}")
    private int configuredPoolSize;

    /** Explicit per-container CPU cap; 0 means derive it. */
    @Value("${code.execution.sandbox.cpus:0}")
    private double configuredCpusPerContainer;

    @Getter
    private int availableCores;

    /** Concurrent submissions the queue will run. */
    @Getter
    private int workerPoolSize;

    /** Docker {@code --cpus} value for one submission's container. */
    @Getter
    private double cpusPerContainer;

    @PostConstruct
    void calculate() {
        availableCores = Runtime.getRuntime().availableProcessors();

        double clampedUtilisation = Math.max(0.1, Math.min(1.0, utilisation));
        double cpuBudget = availableCores * clampedUtilisation;

        if (configuredPoolSize > 0) {
            workerPoolSize = configuredPoolSize;
        } else {
            // One worker per budgeted core, but always at least two so a
            // single slow submission can't block everyone else's queue.
            workerPoolSize = Math.max(2, (int) Math.floor(cpuBudget));
        }

        if (configuredCpusPerContainer > 0) {
            cpusPerContainer = configuredCpusPerContainer;
        } else {
            // Spread the budget across the workers. Capped at one core: a
            // student's submission is single-threaded in practice, so more
            // than a core buys nothing and just starves its neighbours.
            double perWorker = cpuBudget / workerPoolSize;
            cpusPerContainer = Math.round(Math.min(1.0, Math.max(0.25, perWorker)) * 100.0) / 100.0;
        }

        log.info("[CpuBudget] {} cores detected, {}% budget -> {} workers x {} CPU "
                        + "({} cores max for student code)",
                availableCores, Math.round(clampedUtilisation * 100),
                workerPoolSize, cpusPerContainer,
                Math.round(workerPoolSize * cpusPerContainer * 100.0) / 100.0);
    }

    /** Docker {@code --cpus} argument for a sandbox container. */
    public String dockerCpusArg() {
        return String.valueOf(cpusPerContainer);
    }
}
