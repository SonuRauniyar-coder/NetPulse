package com.netpulse.settings;

import com.netpulse.audit.AuditService;
import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.error.ApiException;
import com.netpulse.subnet.SubnetCalculator;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;

/**
 * Settings an admin can change at runtime. The configuration files provide the defaults until the first save.
 * The engine reads these on every cycle, so the value is cached and dropped after a committed change.
 */
@Service
public class SettingsService {
    public record Values(double degradedRttMs, double degradedLossPct, int downAfterFailures, int upAfterSuccesses,
                         int defaultIntervalSeconds, int rawDays, int rollupDays, List<String> scanCidrs) {
        public NetPulseProperties.Thresholds thresholds() {
            return new NetPulseProperties.Thresholds(degradedRttMs, degradedLossPct, downAfterFailures, upAfterSuccesses);
        }
    }

    private final JdbcTemplate jdbc;
    private final NetPulseProperties props;
    private final AuditService audit;
    private volatile Values cached;

    public SettingsService(JdbcTemplate jdbc, NetPulseProperties props, AuditService audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
    }

    public Values current() {
        Values v = cached;
        if (v == null) {
            v = load();
            cached = v;
        }
        return v;
    }

    private Values load() {
        try {
            return jdbc.queryForObject("""
                    select degraded_rtt_ms, degraded_loss_pct, down_after, up_after, default_interval, raw_days, rollup_days, scan_cidrs
                    from app_settings where id = 1""",
                    (rs, n) -> new Values(rs.getDouble(1), rs.getDouble(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                            Arrays.stream(rs.getString(8).split(",")).filter(s -> !s.isBlank()).toList()));
        } catch (EmptyResultDataAccessException e) {
            var t = props.monitoring().thresholds();
            return new Values(t.degradedRttMs(), t.degradedLossPct(), t.downAfterFailures(), t.upAfterSuccesses(),
                    props.monitoring().defaultIntervalSeconds(), props.retention().rawDays(), props.retention().rollupDays(),
                    List.copyOf(props.discovery().authorizedCidrs()));
        }
    }

    @Transactional
    public Values update(Values v) {
        if (v.rollupDays() < v.rawDays()) throw ApiException.badRequest("Rollups must be kept at least as long as raw data");
        List<String> cidrs = v.scanCidrs().stream().map(SettingsService::validateAuthorizedCidr).distinct().toList();
        Values before = current();
        jdbc.update("""
                insert into app_settings (id, degraded_rtt_ms, degraded_loss_pct, down_after, up_after, default_interval, raw_days, rollup_days, scan_cidrs, updated_at)
                values (1, ?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (id) do update set degraded_rtt_ms = excluded.degraded_rtt_ms, degraded_loss_pct = excluded.degraded_loss_pct,
                    down_after = excluded.down_after, up_after = excluded.up_after, default_interval = excluded.default_interval,
                    raw_days = excluded.raw_days, rollup_days = excluded.rollup_days, scan_cidrs = excluded.scan_cidrs, updated_at = now()""",
                v.degradedRttMs(), v.degradedLossPct(), v.downAfterFailures(), v.upAfterSuccesses(), v.defaultIntervalSeconds(),
                v.rawDays(), v.rollupDays(), String.join(",", cidrs));
        audit.record("SETTINGS_UPDATE", "settings", 1, "Settings changed. Authorized scan ranges: " + before.scanCidrs() + " -> " + cidrs);
        cached = null;
        // A reader between our write and the commit could re-cache the old row, so drop the cache again afterwards.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { cached = null; }
        });
        return new Values(v.degradedRttMs(), v.degradedLossPct(), v.downAfterFailures(), v.upAfterSuccesses(),
                v.defaultIntervalSeconds(), v.rawDays(), v.rollupDays(), cidrs);
    }

    /** An authorized range must be unicast IPv4 space. Admins may add public ranges they own, deliberately and audited. */
    private static String validateAuthorizedCidr(String raw) {
        SubnetCalculator.Cidr c = SubnetCalculator.parse(raw);
        int firstOctet = (int) (c.network() >>> 24);
        if (firstOctet == 0 || firstOctet >= 224) throw ApiException.badRequest(raw + " is not unicast address space");
        if (c.prefix() < 8) throw ApiException.badRequest(raw + " is too broad to authorize; use /8 or longer");
        return c.toString();
    }
}
