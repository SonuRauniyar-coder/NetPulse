package com.netpulse.auth;

import com.netpulse.common.config.NetPulseProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static NetPulseProperties props(int perMinute) {
        return new NetPulseProperties(false, new NetPulseProperties.Security("x", Duration.ofMinutes(15), Duration.ofDays(7),
                false, List.of(), 5, Duration.ofMinutes(15), perMinute, null, null), null, null, null, null, null, null, null);
    }

    @Test void blocksAfterCapacityThenRefills() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(props(2), clock);
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
        clock.now = clock.now.plusSeconds(30);
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
    }

    @Test void clientsAreIndependent() {
        LoginRateLimiter limiter = new LoginRateLimiter(props(1), new MutableClock());
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("2.2.2.2")).isTrue();
    }
}
