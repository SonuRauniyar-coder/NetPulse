package com.netpulse.snmp;

import com.netpulse.common.config.NetPulseProperties;
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

/** Same dispatch design as MonitoringScheduler: poll a due-time column, bound concurrency, never overlap a device. */
@Component
@ConditionalOnProperty(name = "netpulse.monitoring.enabled", havingValue = "true")
public class SnmpScheduler {
    private static final Logger log = LoggerFactory.getLogger(SnmpScheduler.class);
    private static final int BATCH = 200;

    private final SnmpStore store;
    private final SnmpPollService poller;
    private final ExecutorService executor;
    private final Clock clock;
    private final Semaphore semaphore;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public SnmpScheduler(SnmpStore store, SnmpPollService poller, ExecutorService monitoringExecutor, NetPulseProperties props, Clock clock) {
        this.store = store;
        this.poller = poller;
        this.executor = monitoringExecutor;
        this.clock = clock;
        this.semaphore = new Semaphore(props.snmp().maxConcurrentPolls());
    }

    @Scheduled(fixedDelay = 2000)
    public void tick() {
        try {
            Instant now = clock.instant();
            for (SnmpStore.Due due : store.findDue(now, BATCH)) {
                if (!inFlight.add(due.deviceId())) continue;
                try {
                    store.scheduleNext(due.deviceId(), now.plusSeconds(due.intervalSeconds()));
                    executor.submit(() -> runGuarded(due.deviceId()));
                } catch (RuntimeException e) {
                    inFlight.remove(due.deviceId());
                    throw e;
                }
            }
        } catch (RuntimeException e) {
            log.error("SNMP tick failed; will retry on the next tick", e);
        }
    }

    private void runGuarded(UUID id) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            poller.poll(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("SNMP poll of device {} failed", id, e);
        } finally {
            if (acquired) semaphore.release();
            inFlight.remove(id);
        }
    }
}
