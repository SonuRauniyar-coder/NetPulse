package com.netpulse.realtime;

import com.netpulse.device.DeviceStatus;
import com.netpulse.monitoring.engine.DeviceSnapshot;
import com.netpulse.monitoring.result.CheckRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class LivePublisher {
    public static final String TOPIC_MONITORING = "/topic/monitoring";
    private static final Logger log = LoggerFactory.getLogger(LivePublisher.class);

    private final SimpMessagingTemplate template;

    public LivePublisher(SimpMessagingTemplate template) {
        this.template = template;
    }

    public record CheckEvent(UUID deviceId, String deviceName, DeviceStatus status, DeviceStatus previousStatus,
                             boolean statusChanged, boolean icmpBlocked, Double lastRttMs, Double lastLossPct,
                             String reason, Instant checkedAt) { }

    /** Called after the DB commit, so clients never see a result that could still roll back. */
    public void publishCheck(DeviceSnapshot device, CheckRecorder.Recorded r) {
        try {
            template.convertAndSend(TOPIC_MONITORING, new CheckEvent(r.deviceId(), device.name(), r.status(), r.previous(),
                    r.statusChanged(), r.assessment().icmpBlocked(), r.rttMs(), r.lossPct(), r.assessment().reason(), r.checkedAt()));
        } catch (RuntimeException e) {
            log.warn("Failed to publish live update: {}", e.getMessage());   // live push is best-effort
        }
    }
}
