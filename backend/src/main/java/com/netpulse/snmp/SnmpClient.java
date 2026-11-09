package com.netpulse.snmp;

import java.net.InetAddress;

public interface SnmpClient {
    /** @throws SnmpPollException with a message safe to show to users */
    SnmpSnapshot poll(InetAddress address, int port, SnmpVersion version, SnmpCredentials credentials);

    class SnmpPollException extends RuntimeException {
        public SnmpPollException(String message) { super(message); }
    }
}
