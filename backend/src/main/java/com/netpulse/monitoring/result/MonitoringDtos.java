package com.netpulse.monitoring.result;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MonitoringDtos {
    private MonitoringDtos() { }

    public record MetricPoint(Instant t, Double rttAvg, Double rttMin, Double rttMax, Double lossPct, Double jitter) { }

    public record PingDetail(int sent, int received, double lossPct, Double rttMin, Double rttAvg, Double rttMax,
                             Double jitter, String errorCategory, String errorDetail) { }

    public record TcpDetail(int port, String state, Double connectMs, String error) { }

    public record DnsDetail(String recordType, String queryName, String dnsServer, String outcome, Double resolveMs,
                            List<String> answers, String error) { }

    public record CheckSummary(long id, Instant checkedAt, String verdict, String status, boolean icmpBlocked,
                               String resolvedIp, String reason, int durationMs, PingDetail ping) { }

    public record CheckDetail(CheckSummary check, List<TcpDetail> tcp, List<DnsDetail> dns) { }

    /** status is null when nothing was observed in the segment: an honest gap, not "assumed up". */
    public record UptimeSegment(Instant start, Instant end, String status, int checks, int up, int degraded, int down) { }

    public record UptimeResponse(Double uptimePct, List<UptimeSegment> segments) { }

    public record StatusEventDto(long id, UUID deviceId, String deviceName, String fromStatus, String toStatus,
                                 String reason, Instant occurredAt) { }
}
