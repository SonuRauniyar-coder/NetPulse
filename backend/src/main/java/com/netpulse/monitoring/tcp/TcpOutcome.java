package com.netpulse.monitoring.tcp;

public record TcpOutcome(int port, TcpState state, Double connectMs, String error) {
}
