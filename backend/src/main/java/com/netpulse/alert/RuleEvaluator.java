package com.netpulse.alert;

import com.netpulse.alert.AlertDecider.Signal;
import com.netpulse.alert.AlertTypes.Rule;
import com.netpulse.device.DeviceStatus;
import com.netpulse.monitoring.tcp.TcpState;
import com.netpulse.snmp.SnmpStore.InterfaceReading;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compares one rule with the latest measurements. Pure: returns one evaluation per subject the rule covers. */
public final class RuleEvaluator {
    private RuleEvaluator() { }

    public record Evaluation(String subject, Signal signal, Double value, String message) { }

    public record CheckFacts(String deviceName, DeviceStatus status, Double rttMs, Double lossPct, Map<Integer, TcpState> ports) { }

    public record SnmpFacts(String deviceName, List<InterfaceReading> interfaces) { }

    public static List<Evaluation> evaluate(Rule r, CheckFacts f) {
        return switch (r.type()) {
            case DEVICE_DOWN -> List.of(switch (f.status()) {
                case DOWN -> new Evaluation("", Signal.BREACH, null, f.deviceName() + " is down");
                case UP, DEGRADED -> new Evaluation("", Signal.CLEAR, null, f.deviceName() + " is reachable");
                case UNKNOWN, PAUSED -> new Evaluation("", Signal.NO_DATA, null, "");
            });
            case RTT_ABOVE -> List.of(threshold(f.rttMs(), r.threshold(), "", f.deviceName() + " RTT", " ms"));
            case LOSS_ABOVE -> List.of(threshold(f.lossPct(), r.threshold(), "", f.deviceName() + " packet loss", "%"));
            case PORT_CLOSED -> {
                List<Evaluation> out = new ArrayList<>();
                f.ports().forEach((port, state) -> {
                    if (r.port() != null && !r.port().equals(port)) return;
                    out.add(state == TcpState.OPEN
                            ? new Evaluation("port:" + port, Signal.CLEAR, null, f.deviceName() + " port " + port + " is open")
                            : new Evaluation("port:" + port, Signal.BREACH, null, f.deviceName() + " port " + port + " is " + describe(state)));
                });
                yield out;
            }
            case INTERFACE_DOWN, INTERFACE_UTILIZATION -> List.of();
        };
    }

    public static List<Evaluation> evaluate(Rule r, SnmpFacts f) {
        List<Evaluation> out = new ArrayList<>();
        for (InterfaceReading i : f.interfaces()) {
            String label = i.descr() == null ? "ifIndex " + i.index() : i.descr();
            if (r.interfaceName() != null && !r.interfaceName().equalsIgnoreCase(label)) continue;
            String subject = "if:" + i.index();
            if (r.type() == AlertTypes.RuleType.INTERFACE_DOWN) {
                out.add(interfaceDown(f.deviceName(), label, subject, i));
            } else if (r.type() == AlertTypes.RuleType.INTERFACE_UTILIZATION) {
                out.add(threshold(i.utilizationPct(), r.threshold(), subject, f.deviceName() + " " + label + " utilization", "%"));
            }
        }
        return out;
    }

    /**
     * ifAdminStatus says what the operator asked for and ifOperStatus says what is happening. An interface someone
     * shut down on purpose isn't a fault, so only "admin up, oper down" raises an alert.
     * ifOperStatus: 1 up, 2 down, 3 testing, 4 unknown, 5 dormant, 6 notPresent, 7 lowerLayerDown.
     */
    private static Evaluation interfaceDown(String device, String label, String subject, InterfaceReading i) {
        if (i.adminStatus() == null || i.operStatus() == null) return new Evaluation(subject, Signal.NO_DATA, null, "");
        if (i.adminStatus() != 1) return new Evaluation(subject, Signal.CLEAR, null, device + " " + label + " is administratively down");
        return switch (i.operStatus()) {
            case 1 -> new Evaluation(subject, Signal.CLEAR, null, device + " " + label + " is up");
            case 2, 7 -> new Evaluation(subject, Signal.BREACH, null, device + " " + label + " is down");
            default -> new Evaluation(subject, Signal.NO_DATA, null, "");
        };
    }

    private static Evaluation threshold(Double value, Double limit, String subject, String label, String unit) {
        if (value == null || limit == null) return new Evaluation(subject, Signal.NO_DATA, null, "");
        return value > limit
                ? new Evaluation(subject, Signal.BREACH, value, label + " is " + number(value) + unit + ", above the " + number(limit) + unit + " limit")
                : new Evaluation(subject, Signal.CLEAR, value, label + " is back to " + number(value) + unit);
    }

    private static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String describe(TcpState s) {
        return s == TcpState.CLOSED ? "closed (connection refused)" : "not answering (filtered or unknown)";
    }
}
