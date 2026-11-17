package com.netpulse.monitoring.engine;

import com.netpulse.common.config.NetPulseProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * The dispatcher. Every second it asks the DB which devices are due and starts a virtual thread for each.
 *
 * <p>A single tick reading {@code next_check_at} was chosen over one timer per device: it survives restarts
 * without rebuilding timers, and an interval change or "check now" is just a column update.
 *
 * <p>Two independent guards:
 * <ul>
 *   <li>{@code semaphore}: at most N cycles run at once. Each cycle spawns a ping process, so unbounded
 *       concurrency would exhaust processes and sockets however cheap virtual threads are.</li>
 *   <li>{@code inFlight}: a device whose previous cycle is still running is skipped, never run twice.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "netpulse.monitoring.enabled", havingValue = "true")
public class MonitoringScheduler {
    private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

    private final DueDeviceStore store;
    private final CheckRunner runner;
    private final ExecutorService executor;
    private final MonitoringMetrics metrics;
    private final Clock clock;
    private final int batchSize;
    private final int maxConcurrent;
    private final Semaphore semaphore;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public MonitoringScheduler(DueDeviceStore store, CheckRunner runner, ExecutorService monitoringExecutor,
                               MonitoringMetrics metrics, NetPulseProperties props, Clock clock, MeterRegistry registry) {
        this.store = store;
        this.runner = runner;
        this.executor = monitoringExecutor;
        this.metrics = metrics;
        this.clock = clock;
        this.batchSize = props.monitoring().dispatchBatchSize();
        this.maxConcurrent = props.monitoring().maxConcurrentChecks();
        this.semaphore = new Semaphore(maxConcurrent);
        Gauge.builder("netpulse.checks.concurrent", semaphore, s -> maxConcurrent - s.availablePermits()).register(registry);
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        try {
            Instant now = clock.instant();
            for (DueDeviceStore.Due due : store.findDue(now, batchSize)) {
                // Leave a busy device due: a host edit or "Check now" made during this cycle runs right after it.
                if (!inFlight.add(due.id())) {
                    metrics.recordSkipped();
                    log.debug("Deferring check for device {}: previous cycle still running", due.id());
                    continue;
                }
                try {
                    // Claim before submitting, so the device is not due again on the next tick while this cycle runs.
                    store.scheduleNext(due.id(), now.plusSeconds(due.intervalSeconds()));
                    executor.submit(() -> runGuarded(due.id()));
                } catch (RuntimeException e) {
                    inFlight.remove(due.id());
                    throw e;
                }
            }
        } catch (RuntimeException e) {
            log.error("Monitoring tick failed; will retry on the next tick", e);   // never let the engine die
        }
    }

    private void runGuarded(UUID deviceId) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            runner.run(deviceId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Check for device {} failed", deviceId, e);
        } finally {
            if (acquired) semaphore.release();
            inFlight.remove(deviceId);
        }
    }
}
