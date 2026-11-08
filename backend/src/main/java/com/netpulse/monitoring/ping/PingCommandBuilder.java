package com.netpulse.monitoring.ping;

import com.netpulse.common.process.OsFamily;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

/**
 * Builds the OS-specific ping argument list. The target is always an already-resolved {@link InetAddress}
 * rendered by the JDK, so user text can never reach the command line.
 *
 * <p>Why not {@code InetAddress.isReachable()}? Without raw-socket privileges the JDK silently falls back to a
 * TCP connection on port 7 (echo) - so it does not measure ICMP at all, reports nothing about RTT or loss, and
 * gives wrong answers for hosts that drop port 7. The OS ping binary is privileged to send real ICMP.
 *
 * <pre>
 *         count   per-reply timeout   numeric (no reverse DNS)
 * Linux   -c N    -W seconds          -n
 * macOS   -c N    -W milliseconds     -n      (ping6 for IPv6, has no -W)
 * Windows -n N    -w milliseconds     (default)
 * </pre>
 */
public final class PingCommandBuilder {
    private PingCommandBuilder() { }

    public static List<String> build(OsFamily os, InetAddress target, int count, Duration replyTimeout) {
        String ip = target.getHostAddress();
        boolean v6 = target instanceof Inet6Address;
        String n = String.valueOf(count);
        return switch (os) {
            case LINUX -> List.of("ping", v6 ? "-6" : "-4", "-n", "-c", n,
                    "-W", String.valueOf(Math.max(1, (long) Math.ceil(replyTimeout.toMillis() / 1000.0))), ip);
            case MACOS -> v6 ? List.of("ping6", "-n", "-c", n, ip)
                    : List.of("ping", "-n", "-c", n, "-W", String.valueOf(replyTimeout.toMillis()), ip);
            case WINDOWS -> List.of("ping", v6 ? "-6" : "-4", "-n", n, "-w", String.valueOf(replyTimeout.toMillis()), ip);
        };
    }

    /** Probes are sent one second apart, so a run lasts about (count-1)s + one reply timeout. A margin is added. */
    public static Duration processTimeout(int count, Duration replyTimeout) {
        return Duration.ofSeconds(count).plus(replyTimeout).plusSeconds(2);
    }
}
