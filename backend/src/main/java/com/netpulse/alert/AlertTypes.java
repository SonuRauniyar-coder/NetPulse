package com.netpulse.alert;

import java.util.Set;

public final class AlertTypes {
    private AlertTypes() { }

    public enum RuleType {
        DEVICE_DOWN, RTT_ABOVE, LOSS_ABOVE, PORT_CLOSED, INTERFACE_DOWN, INTERFACE_UTILIZATION;

        public boolean needsThreshold() { return this == RTT_ABOVE || this == LOSS_ABOVE || this == INTERFACE_UTILIZATION; }
        public boolean usesInterface() { return this == INTERFACE_DOWN || this == INTERFACE_UTILIZATION; }
    }

    public static final Set<RuleType> CHECK_RULES = Set.of(RuleType.DEVICE_DOWN, RuleType.RTT_ABOVE, RuleType.LOSS_ABOVE, RuleType.PORT_CLOSED);
    public static final Set<RuleType> SNMP_RULES = Set.of(RuleType.INTERFACE_DOWN, RuleType.INTERFACE_UTILIZATION);

    /** Declared most severe first, so ordinal() can be compared against a channel's minimum. */
    public enum Severity { CRITICAL, WARNING, INFO }

    public enum State { TRIGGERED, ACKNOWLEDGED, RESOLVED }

    public record Rule(java.util.UUID id, String name, RuleType type, Severity severity, java.util.UUID deviceId, Double threshold,
                       Integer port, String interfaceName, int durationSeconds, boolean enabled) { }
}
