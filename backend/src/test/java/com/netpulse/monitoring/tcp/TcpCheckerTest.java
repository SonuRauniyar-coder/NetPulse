package com.netpulse.monitoring.tcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class TcpCheckerTest {
    private final TcpChecker checker = new TcpChecker(1000);

    @Test void realOpenPort() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            TcpOutcome o = checker.check(InetAddress.getLoopbackAddress(), server.getLocalPort());
            assertThat(o.state()).isEqualTo(TcpState.OPEN);
            assertThat(o.connectMs()).isNotNull();
        }
    }

    @Test void realClosedPortIsRefused() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        TcpOutcome o = checker.check(InetAddress.getLoopbackAddress(), port);
        assertThat(o.state()).isEqualTo(TcpState.CLOSED);
        assertThat(o.connectMs()).isNull();
    }

    @Test void onlyRefusedIsClosed() {
        assertThat(TcpChecker.classify(new ConnectException("Connection refused"))).isEqualTo(TcpState.CLOSED);
        assertThat(TcpChecker.classify(new ConnectException("Connection timed out"))).isEqualTo(TcpState.FILTERED_OR_UNKNOWN);
        assertThat(TcpChecker.classify(new SocketTimeoutException("connect timed out"))).isEqualTo(TcpState.FILTERED_OR_UNKNOWN);
        assertThat(TcpChecker.classify(new NoRouteToHostException("No route to host"))).isEqualTo(TcpState.FILTERED_OR_UNKNOWN);
    }
}
