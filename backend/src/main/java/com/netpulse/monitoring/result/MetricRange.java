package com.netpulse.monitoring.result;

import com.netpulse.common.error.ApiException;

import java.time.Duration;
import java.util.Arrays;

/** Chart ranges. Wider ranges use coarser buckets so a chart never has to draw thousands of points. */
public enum MetricRange {
    H1("1h", Duration.ofHours(1), "1 minute", false),
    H6("6h", Duration.ofHours(6), "5 minutes", false),
    H24("24h", Duration.ofHours(24), "15 minutes", false),
    D7("7d", Duration.ofDays(7), "1 hour", true);

    public final String label;
    public final Duration span;
    public final String bucket;
    public final boolean useRollups;

    MetricRange(String label, Duration span, String bucket, boolean useRollups) {
        this.label = label;
        this.span = span;
        this.bucket = bucket;
        this.useRollups = useRollups;
    }

    public static MetricRange parse(String s) {
        return Arrays.stream(values()).filter(r -> r.label.equals(s)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("range must be one of 1h, 6h, 24h, 7d"));
    }
}
