package com.netpulse.common.web;

import com.netpulse.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding one-minute limit per user and tool for heavy endpoints. In memory, like the rest of the single-instance design. */
@Component
public class ToolRateLimiter {
    private static final long WINDOW_MS = 60_000;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    public ToolRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void acquire(String tool, String user, int perMinute) {
        long now = clock.millis();
        Deque<Long> q = hits.computeIfAbsent(tool + ':' + user, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= WINDOW_MS) q.pollFirst();
            if (q.size() >= perMinute) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                        "You can run this at most " + perMinute + " times a minute. Wait and try again.");
            }
            q.addLast(now);
        }
    }
}
