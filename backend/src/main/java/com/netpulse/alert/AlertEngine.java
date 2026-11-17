package com.netpulse.alert;

import com.netpulse.alert.AlertDecider.Action;
import com.netpulse.alert.AlertTypes.*;
import com.netpulse.alert.RuleEvaluator.Evaluation;
import com.netpulse.notification.NotificationOutbox;
import com.netpulse.realtime.LivePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Runs after a check or SNMP poll has committed. Alert changes and their notifications are written in one
 * transaction, so an alert can't exist without its notification or the reverse. Notifications are only created on
 * a state change; a condition that stays bad produces nothing further.
 */
@Service
public class AlertEngine {
    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private final AlertStore store;
    private final NotificationOutbox outbox;
    private final LivePublisher publisher;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AlertEngine(AlertStore store, NotificationOutbox outbox, LivePublisher publisher, TransactionTemplate tx, Clock clock) {
        this.store = store;
        this.outbox = outbox;
        this.publisher = publisher;
        this.tx = tx;
        this.clock = clock;
    }

    public void onCheck(UUID deviceId, RuleEvaluator.CheckFacts facts) {
        run(deviceId, AlertTypes.CHECK_RULES, facts.deviceName(), r -> RuleEvaluator.evaluate(r, facts));
    }

    public void onSnmp(UUID deviceId, RuleEvaluator.SnmpFacts facts) {
        run(deviceId, AlertTypes.SNMP_RULES, facts.deviceName(), r -> RuleEvaluator.evaluate(r, facts));
    }

    /** A fault in alerting must never break monitoring, so exceptions stop here. */
    private void run(UUID deviceId, Set<RuleType> types, String deviceName, Function<Rule, List<Evaluation>> evaluate) {
        try {
            int changes = Objects.requireNonNull(tx.execute(s -> evaluate(deviceId, types, deviceName, evaluate)));
            if (changes > 0) publisher.publishAlertChange();
        } catch (RuntimeException e) {
            log.error("Alert evaluation failed for device {}", deviceId, e);
        }
    }

    private int evaluate(UUID deviceId, Set<RuleType> types, String deviceName, Function<Rule, List<Evaluation>> evaluate) {
        store.lockDevice(deviceId);
        Instant now = clock.instant();
        List<Rule> rules = store.rulesFor(deviceId, types);
        if (rules.isEmpty()) return 0;
        boolean muted = store.inMaintenance(deviceId, now);
        Map<String, AlertStore.Open> open = new HashMap<>();
        store.openFor(deviceId).forEach(a -> open.put(AlertStore.key(a.ruleId(), a.subject()), a));
        Map<String, Instant> pending = store.pendingFor(deviceId);

        int changes = 0;
        for (Rule rule : rules) {
            Set<String> evaluated = new HashSet<>();
            for (Evaluation e : evaluate.apply(rule)) {
                String key = AlertStore.key(rule.id(), e.subject());
                evaluated.add(key);
                AlertStore.Open current = open.get(key);
                Action action = AlertDecider.decide(e.signal(), current != null, pending.get(key), rule.durationSeconds(), now, muted);
                switch (action) {
                    case NONE -> { }
                    case START_PENDING -> store.startPending(rule.id(), deviceId, e.subject(), now);
                    case CLEAR_PENDING -> store.clearPending(rule.id(), deviceId, e.subject());
                    case TRIGGER -> {
                        store.clearPending(rule.id(), deviceId, e.subject());
                        UUID id = UUID.randomUUID();
                        store.insertAlert(id, rule, deviceId, e.subject(), e.message(), e.value(), now);
                        outbox.enqueue(id, rule.severity(), State.TRIGGERED, e.message(), now);
                        changes++;
                    }
                    case RESOLVE -> {
                        store.resolve(current.id(), "Condition cleared", now);
                        if (!muted) outbox.enqueue(current.id(), current.severity(), State.RESOLVED, e.message(), now);
                        changes++;
                    }
                }
            }
            // The port or interface this alert was about no longer exists, so nothing else could ever resolve it.
            for (AlertStore.Open a : open.values()) {
                if (rule.id().equals(a.ruleId()) && !evaluated.contains(AlertStore.key(rule.id(), a.subject()))) {
                    store.resolve(a.id(), "No longer monitored", now);
                    changes++;
                }
            }
        }
        return changes;
    }

    /** Joins the caller's transaction. Used when a device is paused or re-pointed: its open alerts can no longer clear on their own. */
    public void resolveForDevice(UUID deviceId, String reason) {
        store.lockDevice(deviceId);
        Instant now = clock.instant();
        store.clearPendingForDevice(deviceId);
        List<AlertStore.Open> open = store.openFor(deviceId);
        resolveAll(open, reason, now);
    }

    /** Joins the caller's transaction. Used when a rule is disabled, deleted or moved to other devices. */
    public void resolveForRule(UUID ruleId, String reason) {
        Instant now = clock.instant();
        store.clearPendingForRule(ruleId);
        resolveAll(store.openForRule(ruleId), reason, now);
    }

    private void resolveAll(List<AlertStore.Open> open, String reason, Instant now) {
        for (AlertStore.Open a : open) {
            store.resolve(a.id(), reason, now);
            outbox.enqueue(a.id(), a.severity(), State.RESOLVED, a.deviceName() + ": " + reason, now);
        }
        if (!open.isEmpty()) publishAfterCommit();
    }

    void publishAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publisher.publishAlertChange(); }
            });
        } else {
            publisher.publishAlertChange();
        }
    }
}
