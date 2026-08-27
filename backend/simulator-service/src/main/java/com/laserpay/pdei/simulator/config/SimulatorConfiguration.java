package com.laserpay.pdei.simulator.config;

import com.laserpay.pdei.common.time.Clocks;
import com.laserpay.pdei.simulator.world.WorldGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Beans for the simulator.
 *
 * <p>The interesting one is the run executor. A simulation run is minutes of work, so it cannot
 * live on an HTTP thread; but it also must not live on an unbounded pool, because two runs
 * sharing one broker interleave and make each other's benchmark numbers meaningless. The pool is
 * therefore sized from {@code pdei.simulator.runs.max-concurrent}, with a small queue and an
 * abort policy - a rejected submission surfaces as a clear error rather than as a run that
 * silently waits half an hour for a slot.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorConfiguration {

    @Bean
    public Clocks simulatorClocks() {
        return Clocks.system();
    }

    /**
     * Stateless and thread-safe: all generation state lives inside one {@code generate} call, so
     * a single bean serves every concurrent run.
     */
    @Bean
    public WorldGenerator worldGenerator() {
        return new WorldGenerator();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService runExecutor(SimulatorProperties properties) {
        int threads = Math.max(1, properties.getRuns().getMaxConcurrent());
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "pdei-sim-run-" + counter.incrementAndGet());
            // Daemon: a shutdown must not wait for an eight-minute emission to finish. The run is
            // marked STOPPED rather than left hanging, and its seed makes it repeatable anyway.
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(threads), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
