package com.netpulse.monitoring.engine;

import com.netpulse.device.DnsRecordType;

import java.util.List;
import java.util.UUID;

/** Immutable copy of what a check needs, so no JPA session stays open during slow network I/O. */
public record DeviceSnapshot(UUID id, String name, String host, List<Integer> ports, List<DnsSpec> dnsChecks) {
    public record DnsSpec(DnsRecordType type, String queryName, String server) { }
}
