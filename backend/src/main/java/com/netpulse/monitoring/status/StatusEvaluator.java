package com.netpulse.monitoring.status;

import com.netpulse.common.config.NetPulseProperties.Thresholds;
import com.netpulse.device.DeviceStatus;
import com.netpulse.monitoring.dns.DnsOutcome;
import com.netpulse.monitoring.ping.PingOutcome;
import com.netpulse.monitoring.tcp.TcpOutcome;
import com.netpulse.monitoring.tcp.TcpState;

import java.util.List;

/**
 * Pure decision logic (no I/O), so every rule is unit-testable.
 *
 * <p><b>Step 1, {@link #assess}:</b> what did this cycle show? A device is reachable if ping got a reply, any TCP
 * port completed a handshake, or a DNS server running on the device answered. An ICMP failure alone never means
 * DOWN, because many firewalls drop ICMP: ping failed + TCP open = reachable, flagged "ICMP blocked".
 *
 * <p><b>Step 2, {@link #next}:</b> flap prevention. One lost cycle is usually noise, so DOWN requires N consecutive
 * failures and leaving DOWN requires M consecutive successes.
 */
public final class StatusEvaluator {
    private StatusEvaluator() { }

    public record Assessment(Verdict verdict, boolean icmpBlocked, String reason) { }

    public record Transition(DeviceStatus status, int failures, int successes) { }

    public static Assessment assess(Observation o, Thresholds t) {
        PingOutcome ping = o.ping();
        boolean pingOk = ping != null && ping.success();
        List<TcpOutcome> tcp = o.tcp();
        List<DnsOutcome> dns = o.dns();

        boolean tcpOpen = tcp.stream().anyMatch(r -> r.state() == TcpState.OPEN);
        boolean dnsOnDeviceAnswered = dns.stream().anyMatch(r -> r.dnsServer() != null
                && r.dnsServer().equalsIgnoreCase(o.deviceHost()) && r.kind().serverResponded());
        boolean reachable = pingOk || tcpOpen || dnsOnDeviceAnswered;

        boolean monitorFault = ping != null && !pingOk && ping.error() != null && ping.error().isMonitorFault();
        if (!reachable) {
            if (monitorFault && tcp.isEmpty()) {
                return new Assessment(Verdict.UNKNOWN, false, "Cannot observe target: " + ping.errorDetail());
            }
            return new Assessment(Verdict.FAIL, false, failureReason(ping, tcp));
        }

        boolean icmpBlocked = !pingOk && !monitorFault;
        if (pingOk && ping.rttAvg() != null && ping.rttAvg() > t.degradedRttMs()) {
            return new Assessment(Verdict.DEGRADED, false,
                    String.format("High latency: %.1f ms > %.0f ms", ping.rttAvg(), t.degradedRttMs()));
        }
        if (pingOk && ping.lossPct() > t.degradedLossPct()) {
            return new Assessment(Verdict.DEGRADED, false,
                    String.format("Packet loss: %.0f%% > %.0f%%", ping.lossPct(), t.degradedLossPct()));
        }
        List<TcpOutcome> badPorts = tcp.stream().filter(r -> r.state() != TcpState.OPEN).toList();
        if (!badPorts.isEmpty()) {
            return new Assessment(Verdict.DEGRADED, icmpBlocked, "Port not open: " + badPorts.stream()
                    .map(r -> r.port() + " (" + r.state() + ")").reduce((a, b) -> a + ", " + b).orElse(""));
        }
        List<DnsOutcome> badDns = dns.stream().filter(r -> !r.success()).toList();
        if (!badDns.isEmpty()) {
            DnsOutcome f = badDns.getFirst();
            return new Assessment(Verdict.DEGRADED, icmpBlocked,
                    "DNS check failed: " + f.recordType() + " " + f.queryName() + " (" + f.kind() + ")");
        }
        return new Assessment(Verdict.UP, icmpBlocked, icmpBlocked ? "Reachable; ICMP blocked" : "All checks passed");
    }

    private static String failureReason(PingOutcome ping, List<TcpOutcome> tcp) {
        String p = ping == null ? "no ping result" : "ping: " + ping.error();
        return tcp.isEmpty() ? "All checks failed (" + p + ")" : "All checks failed (" + p + ", no TCP port open)";
    }

    public static Transition next(DeviceStatus current, int failures, int successes, Verdict verdict, Thresholds t) {
        return switch (verdict) {
            // We learned nothing about the target: report UNKNOWN and leave the counters alone.
            case UNKNOWN -> new Transition(DeviceStatus.UNKNOWN, failures, successes);
            case FAIL -> {
                int f = failures + 1;
                // Not enough failures yet: keep showing the previous state.
                yield new Transition(f >= t.downAfterFailures() ? DeviceStatus.DOWN : current, f, 0);
            }
            case UP, DEGRADED -> {
                int s = successes + 1;
                DeviceStatus healthy = verdict == Verdict.UP ? DeviceStatus.UP : DeviceStatus.DEGRADED;
                boolean stayDown = current == DeviceStatus.DOWN && s < t.upAfterSuccesses();
                yield new Transition(stayDown ? DeviceStatus.DOWN : healthy, 0, s);
            }
        };
    }
}
