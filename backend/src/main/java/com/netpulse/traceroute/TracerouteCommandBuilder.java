package com.netpulse.traceroute;

import com.netpulse.common.process.OsFamily;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

/**
 * The target is an already-validated InetAddress rendered by the JDK, so user text never reaches the command line.
 * Hostnames are left on (no -n) because each hop's reverse DNS name is part of the result.
 */
public final class TracerouteCommandBuilder {
    private TracerouteCommandBuilder() { }

    public static List<String> build(OsFamily os, InetAddress target, int maxHops, int probes, Duration probeTimeout) {
        String ip = target.getHostAddress();
        boolean v6 = target instanceof Inet6Address;
        String hops = String.valueOf(maxHops);
        String q = String.valueOf(probes);
        String waitSeconds = String.valueOf(Math.max(1, probeTimeout.toSeconds()));
        return switch (os) {
            // -N 1: send one probe at a time. The default sends 16 at once, so hops would arrive in a batch rather than live.
            case LINUX -> List.of("traceroute", v6 ? "-6" : "-4", "-N", "1", "-m", hops, "-q", q, "-w", waitSeconds, ip);
            case MACOS -> List.of(v6 ? "traceroute6" : "traceroute", "-m", hops, "-q", q, "-w", waitSeconds, ip);
            case WINDOWS -> List.of("tracert", v6 ? "-6" : "-4", "-h", hops, "-w", String.valueOf(probeTimeout.toMillis()), ip);
        };
    }
}
