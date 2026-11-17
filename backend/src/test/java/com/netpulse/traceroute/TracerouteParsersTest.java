package com.netpulse.traceroute;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** macOS samples were captured on the dev machine. Linux and Windows samples follow each tool's documented format. */
class TracerouteParsersTest {
    private final UnixTracerouteParser unix = new UnixTracerouteParser();
    private final WindowsTracerouteParser windows = new WindowsTracerouteParser();

    @Test void linuxNamedHop() {
        var h = unix.parseLine(" 1  _gateway (192.168.1.1)  0.512 ms  0.401 ms  0.388 ms").orElseThrow();
        assertThat(h.hop()).isEqualTo(1);
        assertThat(h.ip()).isEqualTo("192.168.1.1");
        assertThat(h.hostname()).isEqualTo("_gateway");
        assertThat(h.rttsMs()).containsExactly(0.512, 0.401, 0.388);
    }

    @Test void silentHopKeepsOneNullPerProbe() {
        var h = unix.parseLine(" 2  * * *").orElseThrow();
        assertThat(h.ip()).isNull();
        assertThat(h.rttsMs()).hasSize(3).containsOnlyNulls();
    }

    @Test void partialReplies() {
        var h = unix.parseLine(" 7  10.0.0.1 (10.0.0.1)  5.1 ms * 6.2 ms").orElseThrow();
        assertThat(h.rttsMs()).isEqualTo(Arrays.asList(5.1, null, 6.2));
        assertThat(h.hostname()).isNull();
    }

    @Test void macOsLoopbackAsCaptured() {
        var h = unix.parseLine(" 1  localhost (127.0.0.1)  0.205 ms  0.088 ms").orElseThrow();
        assertThat(h.ip()).isEqualTo("127.0.0.1");
        assertThat(h.rttsMs()).containsExactly(0.205, 0.088);
    }

    @Test void loadBalancedRespondersOnOneLine() {
        var h = unix.parseLine(" 3  a.example (10.0.0.1)  5.1 ms  b.example (10.0.0.2)  6.2 ms *").orElseThrow();
        assertThat(h.ip()).isEqualTo("10.0.0.1");
        assertThat(h.otherIps()).containsExactly("10.0.0.2");
        assertThat(h.rttsMs()).isEqualTo(Arrays.asList(5.1, 6.2, null));
    }

    @Test void macOsContinuationLineMergesIntoThePreviousHop() {
        var first = unix.parseLine(" 7  r1.example (72.14.232.85)  12.3 ms").orElseThrow();
        var more = unix.parseLine("    r2.example (108.170.247.129)  12.1 ms  12.6 ms").orElseThrow();
        assertThat(more.hop()).isZero();
        var merged = TracerouteService.merge(first, more);
        assertThat(merged.hop()).isEqualTo(7);
        assertThat(merged.otherIps()).containsExactly("108.170.247.129");
        assertThat(merged.rttsMs()).containsExactly(12.3, 12.1, 12.6);
    }

    @Test void unreachableAnnotation() {
        var h = unix.parseLine(" 4  10.9.9.9 (10.9.9.9)  7.1 ms !H  7.0 ms !H  7.2 ms !H").orElseThrow();
        assertThat(h.note()).isEqualTo("!H");
        assertThat(h.rttsMs()).hasSize(3);
    }

    @Test void numericOutputWithoutParentheses() {
        var h = unix.parseLine(" 2  10.0.0.1  3.1 ms  3.0 ms  2.9 ms").orElseThrow();
        assertThat(h.ip()).isEqualTo("10.0.0.1");
        assertThat(h.hostname()).isNull();
    }

    @Test void headersAreNotHops() {
        assertThat(unix.parseLine("traceroute to 1.1.1.1 (1.1.1.1), 30 hops max, 60 byte packets")).isEmpty();
        assertThat(unix.parseLine("traceroute: Warning: example.com has multiple addresses; using 1.2.3.4")).isEmpty();
        assertThat(windows.parseLine("Tracing route to one.one.one.one [1.1.1.1]")).isEmpty();
        assertThat(windows.parseLine("over a maximum of 30 hops:")).isEmpty();
        assertThat(windows.parseLine("Trace complete.")).isEmpty();
    }

    @Test void windowsSubMillisecondAndBareIp() {
        var h = windows.parseLine("  1    <1 ms    <1 ms    <1 ms  192.168.1.1").orElseThrow();
        assertThat(h.hop()).isEqualTo(1);
        assertThat(h.ip()).isEqualTo("192.168.1.1");
        assertThat(h.rttsMs()).containsExactly(1.0, 1.0, 1.0);
    }

    @Test void windowsNamedHop() {
        var h = windows.parseLine("  3    12 ms    11 ms    13 ms  edge.example.net [203.0.113.9]").orElseThrow();
        assertThat(h.ip()).isEqualTo("203.0.113.9");
        assertThat(h.hostname()).isEqualTo("edge.example.net");
    }

    @Test void windowsTimeoutIsLocalizedSoOnlyStructureCounts() {
        for (String text : List.of("Request timed out.", "Zeitüberschreitung der Anforderung.")) {
            var h = windows.parseLine("  2     *        *        *     " + text).orElseThrow();
            assertThat(h.ip()).isNull();
            assertThat(h.rttsMs()).hasSize(3).containsOnlyNulls();
        }
    }

    @Test void windowsPartialReplies() {
        var h = windows.parseLine("  5    10 ms     *       12 ms  10.0.0.5").orElseThrow();
        assertThat(h.rttsMs()).isEqualTo(Arrays.asList(10.0, null, 12.0));
    }
}
