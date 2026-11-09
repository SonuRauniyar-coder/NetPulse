package com.netpulse.monitoring.retention;

import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Scheduled rollup + cleanup. Exceptions are logged, never allowed to break future runs. */
@Component
@ConditionalOnProperty(name = "netpulse.monitoring.enabled", havingValue = "true")
public class RetentionJobs {
    private static final Logger log = LoggerFactory.getLogger(RetentionJobs.class);

    private final RollupService rollups;
    private final JdbcTemplate jdbc;
    private final NetPulseProperties.Retention cfg;
    private final SettingsService settings;
    private final Clock clock;

    public RetentionJobs(RollupService rollups, JdbcTemplate jdbc, NetPulseProperties props, SettingsService settings, Clock clock) {
        this.rollups = rollups;
        this.jdbc = jdbc;
        this.cfg = props.retention();
        this.settings = settings;
        this.clock = clock;
    }

    /** Every 5 minutes, from the last finished bucket: catches up automatically after downtime. */
    @Scheduled(cron = "0 */5 * * * *")
    public void rollup() {
        try {
            Instant now = clock.instant();
            Instant rawHorizon = now.minus(Duration.ofDays(settings.current().rawDays()));
            Instant from = rollups.latestBucketOr(rawHorizon);
            Instant twoHoursAgo = now.minus(Duration.ofHours(2));
            rollups.rollup(from.isBefore(twoHoursAgo) ? from : twoHoursAgo, now);
        } catch (RuntimeException e) {
            log.error("Rollup failed", e);
        }
    }

    @Scheduled(cron = "0 30 * * * *")
    public void cleanup() {
        try {
            Instant now = clock.instant();
            Instant rawCutoff = now.minus(Duration.ofDays(settings.current().rawDays()));
            Instant longCutoff = now.minus(Duration.ofDays(settings.current().rollupDays()));
            long raw = deleteInBatches("device_check", "id", "checked_at", rawCutoff);
            long events = deleteInBatches("status_event", "id", "occurred_at", longCutoff);
            long rolled = jdbc.update("delete from rollup_hourly where bucket < ?", Timestamp.from(longCutoff));
            deleteInBatches("snmp_sample", "id", "sampled_at", rawCutoff);
            jdbc.update("delete from discovery_scan where started_at < ?", Timestamp.from(longCutoff));
            jdbc.update("delete from alert where state = 'RESOLVED' and resolved_at < ?", Timestamp.from(longCutoff));   // deliveries cascade
            jdbc.update("delete from maintenance_window where ends_at < ?", Timestamp.from(longCutoff));
            if (raw + events + rolled > 0) log.info("Retention removed {} checks, {} events, {} rollups", raw, events, rolled);
        } catch (RuntimeException e) {
            log.error("Retention cleanup failed", e);
        }
    }

    /** Small transactions avoid long row locks and huge WAL bursts. Table names are constants, never user input. */
    private long deleteInBatches(String table, String key, String timeColumn, Instant cutoff) {
        String sql = "delete from " + table + " where " + key + " in (select " + key + " from " + table
                + " where " + timeColumn + " < ? limit ?)";
        long total = 0;
        int n;
        do {
            n = jdbc.update(sql, Timestamp.from(cutoff), cfg.deleteBatchSize());
            total += n;
        } while (n == cfg.deleteBatchSize());
        return total;
    }
}
