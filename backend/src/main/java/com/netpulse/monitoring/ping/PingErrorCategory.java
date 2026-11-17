package com.netpulse.monitoring.ping;

public enum PingErrorCategory {
    /** No reply within the deadline. Could be a dead host, a firewall dropping ICMP, or packet loss. */
    TIMEOUT,
    /** A router (or our own stack) answered with ICMP Destination Unreachable, or there is no route. */
    HOST_UNREACHABLE,
    DNS_FAILURE,
    /** The monitor is not allowed to open an ICMP socket. Says nothing about the target. */
    PERMISSION_DENIED,
    /** The ping binary is not installed. Says nothing about the target. */
    TOOL_MISSING,
    UNKNOWN;

    /** True when the failure is about OUR side, so it is not evidence that the target is down. */
    public boolean isMonitorFault() {
        return this == PERMISSION_DENIED || this == TOOL_MISSING || this == UNKNOWN;
    }
}
