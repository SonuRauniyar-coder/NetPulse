package com.netpulse.monitoring.ping;

import java.util.List;

/** Pure latency math, computed from the individual samples so results are identical on every OS. */
public final class PingStats {
    private PingStats() { }

    /** Packet loss % = (sent - received) / sent * 100, from actual probe counts. */
    public static double lossPct(int sent, int received) {
        if (sent <= 0) return 100.0;
        int r = Math.min(received, sent); // duplicates must never produce negative loss
        return (sent - r) * 100.0 / sent;
    }

    public static Double min(List<Double> v) {
        return v.isEmpty() ? null : v.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
    }

    public static Double max(List<Double> v) {
        return v.isEmpty() ? null : v.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
    }

    public static Double avg(List<Double> v) {
        return v.isEmpty() ? null : v.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    /**
     * Jitter = mean absolute difference between consecutive RTT samples:
     * <pre>  J = ( |rtt2-rtt1| + |rtt3-rtt2| + ... + |rttN-rttN-1| ) / (N-1)</pre>
     * It measures how much latency VARIES, which is what hurts voice/video. Needs at least two samples.
     * (RTP's RFC 3550 jitter is an exponentially smoothed variant of the same idea.)
     */
    public static Double jitter(List<Double> v) {
        if (v.size() < 2) return null;
        double sum = 0;
        for (int i = 1; i < v.size(); i++) {
            sum += Math.abs(v.get(i) - v.get(i - 1));
        }
        return sum / (v.size() - 1);
    }
}
