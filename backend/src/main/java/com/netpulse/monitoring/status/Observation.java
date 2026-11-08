package com.netpulse.monitoring.status;

import com.netpulse.monitoring.dns.DnsOutcome;
import com.netpulse.monitoring.ping.PingOutcome;
import com.netpulse.monitoring.tcp.TcpOutcome;

import java.util.List;

/** Everything measured in one cycle. {@code deviceHost} tells whether a DNS check queried the device itself. */
public record Observation(PingOutcome ping, List<TcpOutcome> tcp, List<DnsOutcome> dns, String deviceHost) {
}
