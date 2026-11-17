package com.netpulse.monitoring.ping;

import java.util.List;

/**
 * Result of one ping run (several ICMP echo requests).
 *
 * @param sent     echo requests sent
 * @param received valid echo replies received (never counts "Destination Unreachable" responses)
 * @param rttsMs   round-trip time of every reply, in arrival order; needed for jitter
 */
public record PingOutcome(int sent, int received, List<Double> rttsMs, PingErrorCategory error, String errorDetail) {

    public PingOutcome {
        rttsMs = List.copyOf(rttsMs);
    }

    public static PingOutcome failure(PingErrorCategory category, String detail, int sent) {
        return new PingOutcome(sent, 0, List.of(), category, detail);
    }

    public boolean success() { return received > 0; }

    public double lossPct() { return PingStats.lossPct(sent, received); }
    public Double rttMin() { return PingStats.min(rttsMs); }
    public Double rttAvg() { return PingStats.avg(rttsMs); }
    public Double rttMax() { return PingStats.max(rttsMs); }
    public Double jitter() { return PingStats.jitter(rttsMs); }
}
