package com.netpulse.monitoring.tcp;

/**
 * OPEN: three-way handshake completed (SYN, SYN-ACK, ACK).
 * CLOSED: the peer actively refused with RST, so the host is alive but nothing listens on that port.
 * FILTERED_OR_UNKNOWN: no answer or an ambiguous error. A silent timeout is often a firewall dropping the SYN,
 * but it can equally be a powered-off host or a broken path, so we refuse to claim which one it is.
 */
public enum TcpState { OPEN, CLOSED, FILTERED_OR_UNKNOWN }
