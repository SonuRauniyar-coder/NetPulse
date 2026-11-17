package com.netpulse.monitoring.dns;

/**
 * NOERROR: answers returned. NODATA: the name exists but has no record of this type (NOERROR, empty answer).
 * NXDOMAIN: the name does not exist. SERVFAIL: the server failed to resolve it (e.g. broken DNSSEC, dead upstream).
 * REFUSED: policy refusal. TIMEOUT: no response. ERROR: anything else.
 */
public enum DnsResultKind {
    NOERROR, NODATA, NXDOMAIN, SERVFAIL, REFUSED, TIMEOUT, ERROR;

    public boolean serverResponded() { return this != TIMEOUT && this != ERROR; }
}
