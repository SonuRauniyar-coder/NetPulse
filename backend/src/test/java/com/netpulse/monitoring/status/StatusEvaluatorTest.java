package com.netpulse.monitoring.status;

import com.netpulse.common.config.NetPulseProperties.Thresholds;
import com.netpulse.device.DeviceStatus;
import com.netpulse.device.DnsRecordType;
import com.netpulse.monitoring.dns.DnsOutcome;
import com.netpulse.monitoring.dns.DnsResultKind;
import com.netpulse.monitoring.ping.PingErrorCategory;
import com.netpulse.monitoring.ping.PingOutcome;
import com.netpulse.monitoring.tcp.TcpOutcome;
import com.netpulse.monitoring.tcp.TcpState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.netpulse.device.DeviceStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class StatusEvaluatorTest {
    private static final Thresholds T = new Thresholds(150, 10, 3, 2);
    private static final PingOutcome GOOD = new PingOutcome(4, 4, List.of(10.0, 11.0, 10.0, 12.0), null, null);
    private static final PingOutcome TIMEOUT = PingOutcome.failure(PingErrorCategory.TIMEOUT, "none", 4);
    private static final TcpOutcome OPEN = new TcpOutcome(443, TcpState.OPEN, 5.0, null);
    private static final TcpOutcome CLOSED = new TcpOutcome(22, TcpState.CLOSED, null, "refused");
    private static final TcpOutcome FILTERED = new TcpOutcome(22, TcpState.FILTERED_OR_UNKNOWN, null, "timeout");

    private static StatusEvaluator.Assessment assess(PingOutcome p, List<TcpOutcome> tcp, List<DnsOutcome> dns) {
        return StatusEvaluator.assess(new Observation(p, tcp, dns, "10.0.0.1"), T);
    }

    @Test void healthy() {
        var a = assess(GOOD, List.of(OPEN), List.of());
        assertThat(a.verdict()).isEqualTo(Verdict.UP);
        assertThat(a.icmpBlocked()).isFalse();
    }

    @Test void icmpFailureWithOpenTcpIsReachableAndFlagged() {
        var a = assess(TIMEOUT, List.of(OPEN), List.of());
        assertThat(a.verdict()).isEqualTo(Verdict.UP);
        assertThat(a.icmpBlocked()).isTrue();
    }

    @Test void allChecksFailedIsFail() {
        assertThat(assess(TIMEOUT, List.of(FILTERED), List.of()).verdict()).isEqualTo(Verdict.FAIL);
        assertThat(assess(TIMEOUT, List.of(), List.of()).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test void highLatencyDegrades() {
        var slow = new PingOutcome(4, 4, List.of(200.0, 210.0, 190.0, 200.0), null, null);
        assertThat(assess(slow, List.of(), List.of()).verdict()).isEqualTo(Verdict.DEGRADED);
    }

    @Test void packetLossDegrades() {
        var lossy = new PingOutcome(4, 3, List.of(10.0, 11.0, 10.0), null, null);
        assertThat(assess(lossy, List.of(), List.of()).verdict()).isEqualTo(Verdict.DEGRADED);
    }

    @Test void closedPortOnReachableHostDegrades() {
        var a = assess(GOOD, List.of(OPEN, CLOSED), List.of());
        assertThat(a.verdict()).isEqualTo(Verdict.DEGRADED);
        assertThat(a.reason()).contains("22").contains("CLOSED");
    }

    @Test void missingPingToolIsNotEvidenceOfOutage() {
        var noTool = PingOutcome.failure(PingErrorCategory.TOOL_MISSING, "'ping' is not installed", 0);
        assertThat(assess(noTool, List.of(), List.of()).verdict()).isEqualTo(Verdict.UNKNOWN);
    }

    @Test void missingPingToolWithOpenTcpIsNotFlaggedIcmpBlocked() {
        var noTool = PingOutcome.failure(PingErrorCategory.TOOL_MISSING, "'ping' is not installed", 0);
        var a = assess(noTool, List.of(OPEN), List.of());
        assertThat(a.verdict()).isEqualTo(Verdict.UP);
        assertThat(a.icmpBlocked()).isFalse();
    }

    @Test void dnsServerOnDeviceAnsweringProvesReachability() {
        var d = new DnsOutcome(DnsRecordType.A, "x.test", "10.0.0.1", DnsResultKind.NXDOMAIN, 5.0, List.of(), "NXDOMAIN");
        assertThat(assess(TIMEOUT, List.of(), List.of(d)).verdict()).isEqualTo(Verdict.DEGRADED);
    }

    @Test void dnsThroughAnotherServerProvesNothingAboutTheDevice() {
        var d = new DnsOutcome(DnsRecordType.A, "x.test", "1.1.1.1", DnsResultKind.NOERROR, 5.0, List.of("1.2.3.4"), null);
        assertThat(assess(TIMEOUT, List.of(), List.of(d)).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test void downOnlyAfterNConsecutiveFailures() {
        var t1 = StatusEvaluator.next(UP, 0, 5, Verdict.FAIL, T);
        assertThat(t1.status()).isEqualTo(UP);
        var t2 = StatusEvaluator.next(t1.status(), t1.failures(), t1.successes(), Verdict.FAIL, T);
        assertThat(t2.status()).isEqualTo(UP);
        var t3 = StatusEvaluator.next(t2.status(), t2.failures(), t2.successes(), Verdict.FAIL, T);
        assertThat(t3.status()).isEqualTo(DOWN);
    }

    @Test void singleSuccessResetsFailureCount() {
        var t = StatusEvaluator.next(UP, 2, 0, Verdict.UP, T);
        assertThat(t.failures()).isZero();
        assertThat(StatusEvaluator.next(UP, t.failures(), t.successes(), Verdict.FAIL, T).status()).isEqualTo(UP);
    }

    @Test void upOnlyAfterMConsecutiveSuccesses() {
        var t1 = StatusEvaluator.next(DOWN, 5, 0, Verdict.UP, T);
        assertThat(t1.status()).isEqualTo(DOWN);
        assertThat(StatusEvaluator.next(t1.status(), t1.failures(), t1.successes(), Verdict.UP, T).status()).isEqualTo(UP);
    }

    @Test void newDeviceStaysUnknownUntilThresholdThenDown() {
        assertThat(StatusEvaluator.next(UNKNOWN, 0, 0, Verdict.FAIL, T).status()).isEqualTo(UNKNOWN);
        assertThat(StatusEvaluator.next(UNKNOWN, 2, 0, Verdict.FAIL, T).status()).isEqualTo(DOWN);
    }

    @Test void newDeviceBecomesUpImmediately() {
        assertThat(StatusEvaluator.next(UNKNOWN, 0, 0, Verdict.UP, T).status()).isEqualTo(UP);
    }

    @Test void unknownVerdictDoesNotTouchCounters() {
        var t = StatusEvaluator.next(UP, 1, 0, Verdict.UNKNOWN, T);
        assertThat(t.status()).isEqualTo(DeviceStatus.UNKNOWN);
        assertThat(t.failures()).isEqualTo(1);
    }
}
