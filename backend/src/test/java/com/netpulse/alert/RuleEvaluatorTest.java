package com.netpulse.alert;

import com.netpulse.alert.AlertDecider.Signal;
import com.netpulse.alert.AlertTypes.Rule;
import com.netpulse.alert.AlertTypes.RuleType;
import com.netpulse.alert.AlertTypes.Severity;
import com.netpulse.alert.RuleEvaluator.CheckFacts;
import com.netpulse.alert.RuleEvaluator.SnmpFacts;
import com.netpulse.device.DeviceStatus;
import com.netpulse.monitoring.tcp.TcpState;
import com.netpulse.snmp.SnmpStore.InterfaceReading;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEvaluatorTest {
    private static Rule rule(RuleType t, Double threshold, Integer port, String iface) {
        return new Rule(UUID.randomUUID(), "r", t, Severity.WARNING, null, threshold, port, iface, 0, true);
    }

    private static CheckFacts check(DeviceStatus s, Double rtt, Double loss, Map<Integer, TcpState> ports) {
        return new CheckFacts("sw1", s, rtt, loss, ports);
    }

    private static Signal only(List<RuleEvaluator.Evaluation> e) {
        assertThat(e).hasSize(1);
        return e.getFirst().signal();
    }

    @Test void deviceDown() {
        Rule r = rule(RuleType.DEVICE_DOWN, null, null, null);
        assertThat(only(RuleEvaluator.evaluate(r, check(DeviceStatus.DOWN, null, null, Map.of())))).isEqualTo(Signal.BREACH);
        assertThat(only(RuleEvaluator.evaluate(r, check(DeviceStatus.UP, 1.0, 0.0, Map.of())))).isEqualTo(Signal.CLEAR);
        assertThat(only(RuleEvaluator.evaluate(r, check(DeviceStatus.DEGRADED, 1.0, 0.0, Map.of())))).isEqualTo(Signal.CLEAR);
        assertThat(only(RuleEvaluator.evaluate(r, check(DeviceStatus.UNKNOWN, null, null, Map.of())))).isEqualTo(Signal.NO_DATA);
    }

    @Test void rttThresholdIsStrictAndMissingRttIsNoData() {
        Rule r = rule(RuleType.RTT_ABOVE, 100.0, null, null);
        assertThat(only(RuleEvaluator.evaluate(r, check(DeviceStatus.UP, 100.1, 0.0, Map.of())))).isEqualTo(Signal.BREACH);
        assertThat(only(RuleEvaluator.evaluate(r, check(DeviceStatus.UP, 100.0, 0.0, Map.of())))).isEqualTo(Signal.CLEAR);
        assertThat(only(RuleEvaluator.evaluate(r, check(DeviceStatus.DOWN, null, 100.0, Map.of())))).isEqualTo(Signal.NO_DATA);
    }

    @Test void lossThreshold() {
        Rule r = rule(RuleType.LOSS_ABOVE, 20.0, null, null);
        var e = RuleEvaluator.evaluate(r, check(DeviceStatus.UP, 1.0, 25.0, Map.of()));
        assertThat(only(e)).isEqualTo(Signal.BREACH);
        assertThat(e.getFirst().message()).contains("25%").contains("20%");
    }

    @Test void portRuleCoversEveryPortOrOne() {
        Map<Integer, TcpState> ports = Map.of(22, TcpState.OPEN, 443, TcpState.CLOSED, 8080, TcpState.FILTERED_OR_UNKNOWN);
        var all = RuleEvaluator.evaluate(rule(RuleType.PORT_CLOSED, null, null, null), check(DeviceStatus.DEGRADED, 1.0, 0.0, ports));
        assertThat(all).hasSize(3);
        assertThat(all.stream().filter(e -> e.signal() == Signal.BREACH)).hasSize(2);
        var one = RuleEvaluator.evaluate(rule(RuleType.PORT_CLOSED, null, 443, null), check(DeviceStatus.DEGRADED, 1.0, 0.0, ports));
        assertThat(one).hasSize(1);
        assertThat(one.getFirst().subject()).isEqualTo("port:443");
        assertThat(one.getFirst().message()).contains("refused");
    }

    private static InterfaceReading iface(int admin, int oper) {
        return new InterfaceReading(3, "Gi0/1", admin, oper, 50.0);
    }

    @Test void interfaceDownMeansAdminUpAndOperDown() {
        Rule r = rule(RuleType.INTERFACE_DOWN, null, null, null);
        assertThat(only(RuleEvaluator.evaluate(r, new SnmpFacts("sw1", List.of(iface(1, 2)))))).isEqualTo(Signal.BREACH);
        assertThat(only(RuleEvaluator.evaluate(r, new SnmpFacts("sw1", List.of(iface(1, 7)))))).isEqualTo(Signal.BREACH);
        assertThat(only(RuleEvaluator.evaluate(r, new SnmpFacts("sw1", List.of(iface(1, 1)))))).isEqualTo(Signal.CLEAR);
    }

    @Test void aDeliberateShutdownIsNotAFault() {
        Rule r = rule(RuleType.INTERFACE_DOWN, null, null, null);
        assertThat(only(RuleEvaluator.evaluate(r, new SnmpFacts("sw1", List.of(iface(2, 2)))))).isEqualTo(Signal.CLEAR);
    }

    @Test void interfaceFilterMatchesByName() {
        Rule r = rule(RuleType.INTERFACE_DOWN, null, null, "gi0/1");
        assertThat(RuleEvaluator.evaluate(r, new SnmpFacts("sw1", List.of(iface(1, 2))))).hasSize(1);
        assertThat(RuleEvaluator.evaluate(rule(RuleType.INTERFACE_DOWN, null, null, "Gi0/2"), new SnmpFacts("sw1", List.of(iface(1, 2))))).isEmpty();
    }

    @Test void utilization() {
        Rule r = rule(RuleType.INTERFACE_UTILIZATION, 40.0, null, null);
        assertThat(only(RuleEvaluator.evaluate(r, new SnmpFacts("sw1", List.of(iface(1, 1)))))).isEqualTo(Signal.BREACH);
        assertThat(only(RuleEvaluator.evaluate(r, new SnmpFacts("sw1", List.of(new InterfaceReading(1, "x", 1, 1, null)))))).isEqualTo(Signal.NO_DATA);
    }
}
