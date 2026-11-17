package com.netpulse.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "netpulse.monitoring.enabled", havingValue = "true")
public class NotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private final NotificationService service;

    public NotificationScheduler(NotificationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 5000)
    public void tick() {
        try {
            service.deliverDue();
        } catch (RuntimeException e) {
            log.error("Notification delivery failed; will retry", e);
        }
    }
}
