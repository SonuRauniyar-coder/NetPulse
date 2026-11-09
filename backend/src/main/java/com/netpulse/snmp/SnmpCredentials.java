package com.netpulse.snmp;

/** Decrypted credentials. Only ever held in memory during a poll; never serialized to a client. */
public record SnmpCredentials(String community, String username, String authProtocol, String authPassword, String privPassword) {
    @Override
    public String toString() {
        return "SnmpCredentials[redacted]";   // a stray log statement must not be able to leak these
    }
}
