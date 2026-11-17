package com.netpulse.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * All tunables in one typed, validated place. Values come from application*.yml, which in turn
 * read secrets from environment variables. Startup fails fast if something is out of range.
 */
@Validated
@ConfigurationProperties(prefix = "netpulse")
public record NetPulseProperties(
        boolean demoMode,
        @Valid @NotNull Security security,
        @Valid @NotNull Monitoring monitoring,
        @Valid @NotNull Retention retention,
        String credentialEncryptionKey,
        @Valid @NotNull Traceroute traceroute,
        @Valid @NotNull Discovery discovery,
        @Valid @NotNull Snmp snmp,
        @Valid @NotNull Notifications notifications) {

    public record Security(
            String jwtSecret,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl,
            boolean cookieSecure,
            @NotNull List<String> allowedOrigins,
            @Min(1) int loginMaxAttempts,
            @NotNull Duration loginLockout,
            @Min(1) int loginRateLimitPerMinute,
            String adminUsername,
            String adminPassword) {
    }

    public record Monitoring(
            boolean enabled,
            @Min(1) @Max(1024) int maxConcurrentChecks,
            @Min(1) int dispatchBatchSize,
            @Min(10) @Max(3600) int defaultIntervalSeconds,
            @Min(1) @Max(20) int pingCount,
            @NotNull Duration pingReplyTimeout,
            @NotNull Duration tcpConnectTimeout,
            @NotNull Duration dnsTimeout,
            @NotNull Duration cycleTimeout,
            @Valid @NotNull Thresholds thresholds) {
    }

    /**
     * DEGRADED when RTT or loss exceed these. DOWN only after {@code downAfterFailures} consecutive failed
     * cycles, back from DOWN only after {@code upAfterSuccesses} consecutive good cycles (flap prevention).
     */
    public record Thresholds(
            @Min(1) double degradedRttMs,
            @Min(0) @Max(100) double degradedLossPct,
            @Min(1) int downAfterFailures,
            @Min(1) int upAfterSuccesses) {
    }

    public record Retention(@Min(1) int rawDays, @Min(1) int rollupDays, @Min(100) int deleteBatchSize) {
    }

    public record Traceroute(@Min(1) @Max(64) int maxHops, @Min(1) @Max(5) int probesPerHop, @NotNull Duration probeTimeout,
                             @NotNull Duration runTimeout, @Min(1) int maxSilentHops, @Min(1) int maxConcurrent,
                             @Min(1) int perUserPerMinute) {
    }

    public record Discovery(@NotNull List<String> authorizedCidrs, @Min(16) @Max(32) int minPrefix,
                            @Min(1) @Max(512) int maxConcurrentProbes, @NotNull Duration probeTimeout,
                            @NotNull Duration maxDuration, @Min(1) int maxConcurrentScans, @Min(1) int perUserPerMinute) {
    }

    public record Snmp(@NotNull Duration timeout, @Min(0) @Max(5) int retries, @Min(1) int maxConcurrentPolls,
                       @Min(10) @Max(3600) int defaultIntervalSeconds, @Min(1) int maxInterfaces) {
    }

    public record Notifications(@NotNull String mailFrom, @Min(1) @Max(20) int maxAttempts,
                                @NotNull Duration initialBackoff, @NotNull Duration webhookTimeout) {
    }
}
