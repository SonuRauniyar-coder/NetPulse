package com.netpulse.monitoring.ping;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PingParsersTest {
    private final PingParser linux = new LinuxPingParser();
    private final PingParser mac = new MacPingParser();
    private final PingParser windows = new WindowsPingParser();

    private static String sample(String path) throws IOException {
        try (InputStream in = PingParsersTest.class.getResourceAsStream("/samples/ping/" + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void linuxSuccess() throws IOException {
        PingOutcome o = linux.parse(sample("linux/success.txt"), 4);
        assertThat(o.sent()).isEqualTo(4);
        assertThat(o.received()).isEqualTo(4);
        assertThat(o.rttsMs()).containsExactly(92.8, 77.9, 75.9, 44.1);
        assertThat(o.lossPct()).isZero();
        assertThat(o.error()).isNull();
    }

    @Test void linuxPartialLossIgnoresDuplicates() throws IOException {
        PingOutcome o = linux.parse(sample("linux/partial-loss.txt"), 4);
        assertThat(o.received()).isEqualTo(3);
        assertThat(o.rttsMs()).containsExactly(10.0, 20.0, 15.0);
        assertThat(o.lossPct()).isEqualTo(25.0);
    }

    @Test void linuxTimeout() throws IOException {
        PingOutcome o = linux.parse(sample("linux/timeout.txt"), 3);
        assertThat(o.sent()).isEqualTo(3);
        assertThat(o.received()).isZero();
        assertThat(o.error()).isEqualTo(PingErrorCategory.TIMEOUT);
        assertThat(o.lossPct()).isEqualTo(100.0);
    }

    @Test void linuxUnreachable() throws IOException {
        assertThat(linux.parse(sample("linux/unreachable.txt"), 3).error()).isEqualTo(PingErrorCategory.HOST_UNREACHABLE);
    }

    @Test void linuxUnknownHost() throws IOException {
        assertThat(linux.parse(sample("linux/unknown-host.txt"), 2).error()).isEqualTo(PingErrorCategory.DNS_FAILURE);
    }

    @Test void linuxPermissionDenied() throws IOException {
        PingOutcome o = linux.parse(sample("linux/permission-denied.txt"), 4);
        assertThat(o.error()).isEqualTo(PingErrorCategory.PERMISSION_DENIED);
        assertThat(o.error().isMonitorFault()).isTrue();
    }

    @Test void macSuccess() throws IOException {
        PingOutcome o = mac.parse(sample("macos/success.txt"), 4);
        assertThat(o.rttsMs()).containsExactly(58.164, 56.815, 60.986, 54.542);
        assertThat(o.rttMin()).isEqualTo(54.542);
        assertThat(o.rttMax()).isEqualTo(60.986);
        assertThat(o.rttAvg()).isCloseTo(57.627, within(0.5));
    }

    @Test void macPing6Format() throws IOException {
        PingOutcome o = mac.parse(sample("macos/ping6-success.txt"), 2);
        assertThat(o.rttsMs()).containsExactly(0.098, 0.202);
    }

    @Test void macPartialLoss() throws IOException {
        PingOutcome o = mac.parse(sample("macos/partial-loss.txt"), 4);
        assertThat(o.received()).isEqualTo(3);
        assertThat(o.lossPct()).isEqualTo(25.0);
    }

    @Test void macTimeout() throws IOException {
        assertThat(mac.parse(sample("macos/timeout.txt"), 3).error()).isEqualTo(PingErrorCategory.TIMEOUT);
    }

    @Test void macUnreachableLineIsNotAReply() throws IOException {
        PingOutcome o = mac.parse(sample("macos/unreachable.txt"), 3);
        assertThat(o.received()).isZero();
        assertThat(o.error()).isEqualTo(PingErrorCategory.HOST_UNREACHABLE);
    }

    @Test void macUnknownHost() throws IOException {
        assertThat(mac.parse(sample("macos/unknown-host.txt"), 2).error()).isEqualTo(PingErrorCategory.DNS_FAILURE);
    }

    @Test void windowsSuccessIncludingSubMillisecond() throws IOException {
        PingOutcome o = windows.parse(sample("windows/success.txt"), 4);
        assertThat(o.sent()).isEqualTo(4);
        assertThat(o.rttsMs()).containsExactly(14.0, 16.0, 13.0, 1.0);
    }

    @Test void windowsGermanLocale() throws IOException {
        PingOutcome o = windows.parse(sample("windows/success-german.txt"), 2);
        assertThat(o.sent()).isEqualTo(2);
        assertThat(o.rttsMs()).containsExactly(14.0, 16.0);
    }

    @Test void windowsPartialLoss() throws IOException {
        PingOutcome o = windows.parse(sample("windows/partial-loss.txt"), 4);
        assertThat(o.received()).isEqualTo(3);
        assertThat(o.lossPct()).isEqualTo(25.0);
    }

    @Test void windowsTimeout() throws IOException {
        assertThat(windows.parse(sample("windows/timeout.txt"), 3).error()).isEqualTo(PingErrorCategory.TIMEOUT);
    }

    /** The classic trap: the summary says "Received = 3, 0% loss" but no echo reply ever arrived. */
    @Test void windowsUnreachableIsNotCountedAsReceived() throws IOException {
        PingOutcome o = windows.parse(sample("windows/unreachable.txt"), 3);
        assertThat(o.received()).isZero();
        assertThat(o.lossPct()).isEqualTo(100.0);
        assertThat(o.error()).isEqualTo(PingErrorCategory.HOST_UNREACHABLE);
    }

    @Test void windowsUnreachableGermanDetectedByShape() throws IOException {
        assertThat(windows.parse(sample("windows/unreachable-german.txt"), 1).error()).isEqualTo(PingErrorCategory.HOST_UNREACHABLE);
    }

    @Test void windowsIpv6ReplyHasNoTtl() {
        PingOutcome o = windows.parse("Reply from ::1: time<1ms\nReply from ::1: time=3ms\n", 2);
        assertThat(o.received()).isEqualTo(2);
        assertThat(o.rttsMs()).containsExactly(1.0, 3.0);
    }

    @Test void partialOutputFromKilledProcessFallsBackToRequestedCount() {
        PingOutcome o = linux.parse("PING 10.0.0.1 (10.0.0.1) 56(84) bytes of data.\n64 bytes from 10.0.0.1: icmp_seq=1 ttl=64 time=5.0 ms\n", 4);
        assertThat(o.sent()).isEqualTo(4);
        assertThat(o.received()).isEqualTo(1);
    }
}
