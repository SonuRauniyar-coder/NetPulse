package com.netpulse.auth;

import com.netpulse.common.config.NetPulseProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client-IP token bucket for the login endpoint (slows down password spraying across many usernames;
 * the per-account lockout in AuthService handles brute force against one username).
 *
 * <p>Token bucket: each IP has {@code capacity} tokens, refilled continuously at capacity/minute.
 * A request costs one token; with none left the request is rejected (HTTP 429).
 * In-memory is fine for a single-instance monolith; multiple instances would need a shared store.
 */
@Component
public class LoginRateLimiter {
    private static final int MAX_TRACKED = 10_000;
    private final int capacity;
    private final Clock clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LoginRateLimiter(NetPulseProperties props, Clock clock) {
        this.capacity = props.security().loginRateLimitPerMinute();
        this.clock = clock;
    }

    private static final class Bucket {
        double tokens;
        long lastRefillMs;
    }

    public boolean tryAcquire(String clientIp) {
        if (buckets.size() > MAX_TRACKED) buckets.clear(); // crude memory bound against IP-spraying
        long now = clock.millis();
        Bucket b = buckets.computeIfAbsent(clientIp, k -> {
            Bucket nb = new Bucket();
            nb.tokens = capacity;
            nb.lastRefillMs = now;
            return nb;
        });
        synchronized (b) {
            double refill = (now - b.lastRefillMs) * capacity / 60_000.0;
            b.tokens = Math.min(capacity, b.tokens + refill);
            b.lastRefillMs = now;
            if (b.tokens < 1) return false;
            b.tokens -= 1;
            return true;
        }
    }
}
