package com.netpulse.monitoring.dns;

import com.netpulse.device.DnsRecordType;

import java.util.List;

public record DnsOutcome(DnsRecordType recordType, String queryName, String dnsServer, DnsResultKind kind,
                         Double resolveMs, List<String> answers, String error) {
    public boolean success() { return kind == DnsResultKind.NOERROR; }
}
