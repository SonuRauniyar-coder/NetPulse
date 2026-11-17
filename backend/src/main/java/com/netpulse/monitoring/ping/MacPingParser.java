package com.netpulse.monitoring.ping;

/**
 * BSD ping on macOS, e.g.
 * <pre>
 * 64 bytes from 8.8.8.8: icmp_seq=0 ttl=111 time=58.164 ms
 * Request timeout for icmp_seq 0
 * 4 packets transmitted, 4 packets received, 0.0% packet loss
 * </pre>
 * ping6 replies differ slightly: "16 bytes from ::1, icmp_seq=0 hlim=64 time=0.098 ms".
 */
public class MacPingParser extends UnixPingParser {
    @Override
    protected boolean isDnsFailure(String o) {
        return o.contains("cannot resolve") || o.contains("unknown host");
    }
}
