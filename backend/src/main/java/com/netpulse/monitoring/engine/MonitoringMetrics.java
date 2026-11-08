package com.netpulse.monitoring.engine;

import com.netpulse.monitoring.status.Verdict;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Exposed at /actuator/prometheus as netpulse_checks_run_total, netpulse_check_failures_total, ... */
@Component
public class MonitoringMetrics {
    private final MeterRegistry registry;
    private final Counter checksRun;
    private final Counter skipped;
    private final Timer duration;

    public MonitoringMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.checksRun = registry.counter("netpulse.checks.run");
        this.skipped = registry.counter("netpulse.checks.skipped");
        this.duration = registry.timer("netpulse.check.duration");
    }

    public void recordCycle(Verdict verdict, int durationMs) {
        checksRun.increment();
        duration.record(Duration.ofMillis(durationMs));
        if (verdict == Verdict.FAIL || verdict == Verdict.UNKNOWN) {
            registry.counter("netpulse.check.failures", "verdict", verdict.name()).increment();
        }
    }

    public void recordSkipped() {
        skipped.increment();
    }
}
