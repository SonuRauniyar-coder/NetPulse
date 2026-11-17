package com.netpulse.monitoring.engine;

import com.netpulse.monitoring.status.Verdict;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Exposed at /actuator/prometheus as netpulse_checks_run_total, netpulse_check_failures_total, ... */
@Component
public class MonitoringMetrics {
    private final MeterRegistry registry;
    private final Counter checksRun;
    private final Counter skipped;
    private final Timer duration;

    public MonitoringMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        Gauge.builder("netpulse.alerts.active", jdbc, j -> {
            Long n = j.queryForObject("select count(*) from alert where state <> 'RESOLVED'", Long.class);
            return n == null ? 0 : n;
        }).register(registry);
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
