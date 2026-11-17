package com.netpulse.monitoring.tcp;

import com.netpulse.common.config.NetPulseProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * TCP port check using a full connect. {@code Socket.connect} returning means the three-way handshake finished;
 * we then close immediately and the elapsed time is the connect latency (roughly one network round trip).
 */
@Component
public class TcpChecker {
    private final int timeoutMs;

    @Autowired
    public TcpChecker(NetPulseProperties props) {
        this((int) props.monitoring().tcpConnectTimeout().toMillis());
    }

    TcpChecker(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public TcpOutcome check(InetAddress address, int port) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            return new TcpOutcome(port, TcpState.OPEN, (System.nanoTime() - start) / 1e6, null);
        } catch (IOException e) {
            return new TcpOutcome(port, classify(e), null, describe(e));
        }
    }

    /** Only an explicit "refused" (RST received) is CLOSED. Everything else is reported as unknown. */
    static TcpState classify(IOException e) {
        if (e instanceof ConnectException && String.valueOf(e.getMessage()).toLowerCase().contains("refused")) {
            return TcpState.CLOSED;
        }
        return TcpState.FILTERED_OR_UNKNOWN;
    }

    private static String describe(IOException e) {
        if (e instanceof SocketTimeoutException) return "No response within timeout";
        if (e instanceof NoRouteToHostException) return "No route to host";
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.substring(0, Math.min(200, m.length()));
    }
}
