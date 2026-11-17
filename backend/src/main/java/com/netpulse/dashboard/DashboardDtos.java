package com.netpulse.dashboard;

import com.netpulse.monitoring.result.MonitoringDtos.StatusEventDto;

import java.util.List;
import java.util.UUID;

public final class DashboardDtos {
    private DashboardDtos() { }

    public record SlowDevice(UUID id, String name, String host, String status, double rttMs) { }

    /** avgLatencyMs / networkUptimePct are null when nothing has been measured yet. */
    public record Summary(long total, long up, long degraded, long down, long unknown, long paused,
                          Double avgLatencyMs, Double networkUptimePct,
                          List<SlowDevice> slowest, List<StatusEventDto> recentChanges) { }
}
