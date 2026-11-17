package com.netpulse.notification;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationUnitsTest {
    @Test void backoffDoublesAndIsCapped() {
        Duration base = Duration.ofSeconds(30);
        assertThat(NotificationService.backoff(base, 1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(NotificationService.backoff(base, 2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(NotificationService.backoff(base, 4)).isEqualTo(Duration.ofSeconds(240));
        assertThat(NotificationService.backoff(base, 30)).isEqualTo(Duration.ofHours(1));
    }
}
