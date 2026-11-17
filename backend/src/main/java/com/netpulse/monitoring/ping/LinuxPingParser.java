package com.netpulse.monitoring.ping;

/**
 * iputils ping, e.g.
 * <pre>
 * 64 bytes from 8.8.8.8: icmp_seq=1 ttl=63 time=92.8 ms
 * 4 packets transmitted, 4 received, 0% packet loss, time 3016ms
 * </pre>
 */
public class LinuxPingParser extends UnixPingParser {
    @Override
    protected boolean isDnsFailure(String o) {
        return o.contains("name or service not known") || o.contains("temporary failure in name resolution");
    }
}
