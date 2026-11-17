package com.netpulse.monitoring.result;

import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.device.DeviceStatus;
import com.netpulse.monitoring.status.Observation;
import com.netpulse.monitoring.status.StatusEvaluator;
import com.netpulse.monitoring.status.StatusEvaluator.Assessment;
import com.netpulse.monitoring.status.StatusEvaluator.Transition;
import com.netpulse.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persists one finished cycle atomically. Network I/O happens BEFORE this, so no DB transaction waits on a slow host. */
@Service
public class CheckRecorder {
    private final CheckResultStore store;
    private final SettingsService settings;
    private final Clock clock;

    public CheckRecorder(CheckResultStore store, SettingsService settings, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    public record Recorded(UUID deviceId, DeviceStatus previous, DeviceStatus status, Assessment assessment,
                           Double rttMs, Double lossPct, Instant checkedAt) {
        public boolean statusChanged() { return previous != status; }
    }

    /** @return empty if the device was paused, deleted or re-pointed while the check ran (result is stale) */
    @Transactional
    public Optional<Recorded> record(UUID deviceId, String hostAtStart, Observation o, String resolvedIp,
                                     Instant checkedAt, int durationMs) {
        Optional<CheckResultStore.DeviceState> locked = store.lockDeviceState(deviceId);
        if (locked.isEmpty() || !locked.get().enabled() || !locked.get().host().equals(hostAtStart)) {
            return Optional.empty();
        }
        CheckResultStore.DeviceState s = locked.get();
        NetPulseProperties.Thresholds thresholds = settings.current().thresholds();
        Assessment a = StatusEvaluator.assess(o, thresholds);
        Transition t = StatusEvaluator.next(s.status(), s.failures(), s.successes(), a.verdict(), thresholds);

        long checkId = store.insertCheck(deviceId, checkedAt, a.verdict(), t.status(), a.icmpBlocked(), resolvedIp, a.reason(), durationMs);
        if (o.ping() != null) store.insertPing(checkId, o.ping());
        store.insertTcp(checkId, o.tcp());
        store.insertDns(checkId, o.dns());

        boolean changed = t.status() != s.status();
        // A transition happens when it is applied, not when its probes began, so the event history stays in order.
        Instant changedAt = changed ? clock.instant() : null;
        if (changed) store.insertStatusEvent(deviceId, s.status(), t.status(), a.reason(), changedAt);

        Double rtt = o.ping() == null ? null : o.ping().rttAvg();
        Double loss = o.ping() == null || o.ping().sent() == 0 ? null : o.ping().lossPct();
        store.updateDeviceState(deviceId, t.status(), a.icmpBlocked(), t.failures(), t.successes(),
                resolvedIp, rtt, loss, checkedAt, changedAt);
        return Optional.of(new Recorded(deviceId, s.status(), t.status(), a, rtt, loss, checkedAt));
    }
}
