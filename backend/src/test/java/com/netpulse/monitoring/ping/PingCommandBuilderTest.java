package com.netpulse.monitoring.ping;

import com.netpulse.common.process.OsFamily;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PingCommandBuilderTest {
    private static final Duration TWO_S = Duration.ofSeconds(2);

    @Test void linuxUsesSecondsForReplyTimeout() throws Exception {
        assertThat(PingCommandBuilder.build(OsFamily.LINUX, InetAddress.getByName("8.8.8.8"), 4, TWO_S))
                .containsExactly("ping", "-4", "-n", "-c", "4", "-W", "2", "8.8.8.8");
    }

    @Test void macUsesMillisecondsForReplyTimeout() throws Exception {
        assertThat(PingCommandBuilder.build(OsFamily.MACOS, InetAddress.getByName("8.8.8.8"), 4, TWO_S))
                .containsExactly("ping", "-n", "-c", "4", "-W", "2000", "8.8.8.8");
    }

    @Test void macIpv6UsesPing6() throws Exception {
        assertThat(PingCommandBuilder.build(OsFamily.MACOS, InetAddress.getByName("::1"), 2, TWO_S).getFirst()).isEqualTo("ping6");
    }

    @Test void windowsFlags() throws Exception {
        assertThat(PingCommandBuilder.build(OsFamily.WINDOWS, InetAddress.getByName("8.8.8.8"), 4, TWO_S))
                .containsExactly("ping", "-4", "-n", "4", "-w", "2000", "8.8.8.8");
    }

    @Test void subSecondTimeoutRoundsUpOnLinux() throws Exception {
        assertThat(PingCommandBuilder.build(OsFamily.LINUX, InetAddress.getByName("8.8.8.8"), 1, Duration.ofMillis(300)))
                .contains("-W", "1");
    }
}
