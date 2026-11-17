package com.netpulse.monitoring.ping;

public interface PingParser {
    /**
     * @param output         combined stdout/stderr of the ping process (possibly partial if it was killed)
     * @param requestedCount how many probes we asked for; fallback when no summary line was printed
     */
    PingOutcome parse(String output, int requestedCount);
}
