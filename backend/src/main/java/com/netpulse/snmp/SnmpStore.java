package com.netpulse.snmp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Storage for SNMP configuration and readings, including the rate calculation that needs the previous sample. */
@Repository
public class SnmpStore {
    private final JdbcTemplate jdbc;

    public SnmpStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Stored(UUID deviceId, boolean enabled, SnmpVersion version, int port, int intervalSeconds, String credentials, String host, String deviceName) { }

    public record InterfaceReading(int index, String descr, Integer adminStatus, Integer operStatus, Double utilizationPct) { }

    public record Due(UUID deviceId, int intervalSeconds) { }

    public Optional<Stored> find(UUID deviceId) {
        return jdbc.query("""
                select c.device_id, c.enabled, c.version, c.port, c.interval_seconds, c.credentials, d.host, d.name
                from snmp_config c join device d on d.id = c.device_id where c.device_id = ?""",
                (rs, n) -> new Stored(rs.getObject(1, UUID.class), rs.getBoolean(2), SnmpVersion.valueOf(rs.getString(3)), rs.getInt(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7), rs.getString(8)), deviceId).stream().findFirst();
    }

    /** Paused devices aren't polled, matching how ping, TCP and DNS treat them. */
    public List<Due> findDue(Instant now, int limit) {
        return jdbc.query("""
                select c.device_id, c.interval_seconds from snmp_config c join device d on d.id = c.device_id
                where c.enabled and d.enabled and (c.next_poll_at is null or c.next_poll_at <= ?)
                order by c.next_poll_at nulls first limit ?""",
                (rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getInt(2)), Timestamp.from(now), limit);
    }

    public void scheduleNext(UUID id, Instant next) {
        jdbc.update("update snmp_config set next_poll_at = ? where device_id = ?", Timestamp.from(next), id);
    }

    public void recordFailure(UUID id, String error, Instant at) {
        jdbc.update("update snmp_config set last_poll_at = ?, last_error = ? where device_id = ?",
                Timestamp.from(at), error.length() > 255 ? error.substring(0, 255) : error, id);
    }

    private record Previous(BigInteger in, BigInteger out, int bits) { }

    /** All-or-nothing, and serialized per device by the row lock so two polls can't compute from the same baseline. */
    @Transactional
    public List<InterfaceReading> recordSuccess(UUID id, SnmpSnapshot s, Instant at) {
        List<Long> lock = jdbc.query("select sys_uptime_ticks from snmp_config where device_id = ? for update",
                (rs, n) -> { long v = rs.getLong(1); return rs.wasNull() ? -1L : v; }, id);
        if (lock.isEmpty()) return List.of();   // configuration removed while the poll ran
        long previousUptime = lock.getFirst();
        // Reboot detection: the agent's uptime went backwards, so previous counters are from a former life.
        boolean baselineValid = previousUptime >= 0 && s.uptimeTicks() > previousUptime;
        double elapsed = baselineValid ? (s.uptimeTicks() - previousUptime) / 100.0 : 0;

        Map<Integer, Previous> before = new HashMap<>();
        jdbc.query("select if_index, in_octets, out_octets, counter_bits from snmp_interface where device_id = ?", rs -> {
            before.put(rs.getInt(1), new Previous(big(rs, 2), big(rs, 3), rs.getInt(4)));
        }, id);

        List<InterfaceReading> readings = new ArrayList<>(s.interfaces().size());
        for (SnmpSnapshot.Interface i : s.interfaces()) {
            Previous p = before.get(i.index());
            // If the agent moved between 32- and 64-bit counters, the two values aren't comparable.
            boolean comparable = baselineValid && p != null && p.bits() == i.counterBits();
            Double in = comparable ? CounterMath.rateBps(p.in(), i.inOctets(), i.counterBits(), elapsed, i.speedBps()).orElse(null) : null;
            Double out = comparable ? CounterMath.rateBps(p.out(), i.outOctets(), i.counterBits(), elapsed, i.speedBps()).orElse(null) : null;
            Double util = CounterMath.utilizationPct(in, out, i.speedBps());
            jdbc.update("""
                    insert into snmp_interface (device_id, if_index, if_descr, admin_status, oper_status, speed_bps, in_octets, out_octets,
                                                counter_bits, in_errors, out_errors, in_bps, out_bps, utilization_pct, last_seen_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (device_id, if_index) do update set if_descr = excluded.if_descr, admin_status = excluded.admin_status,
                        oper_status = excluded.oper_status, speed_bps = excluded.speed_bps, in_octets = excluded.in_octets, out_octets = excluded.out_octets,
                        counter_bits = excluded.counter_bits, in_errors = excluded.in_errors, out_errors = excluded.out_errors, in_bps = excluded.in_bps,
                        out_bps = excluded.out_bps, utilization_pct = excluded.utilization_pct, last_seen_at = excluded.last_seen_at""",
                    id, i.index(), truncate(i.descr(), 255), i.adminStatus(), i.operStatus(), i.speedBps(), dec(i.inOctets()), dec(i.outOctets()),
                    i.counterBits(), dec(i.inErrors()), dec(i.outErrors()), in, out, util, Timestamp.from(at));
            if (in != null && out != null) {
                jdbc.update("insert into snmp_sample (device_id, if_index, sampled_at, in_bps, out_bps, utilization_pct) values (?, ?, ?, ?, ?, ?)",
                        id, i.index(), Timestamp.from(at), in, out, util);
            }
            readings.add(new InterfaceReading(i.index(), i.descr(), i.adminStatus(), i.operStatus(), util));
        }
        jdbc.update("delete from snmp_interface where device_id = ? and last_seen_at <> ?", id, Timestamp.from(at));   // removed from the device
        jdbc.update("""
                update snmp_config set sys_name = ?, sys_descr = ?, sys_location = ?, sys_uptime_ticks = ?,
                    last_poll_at = ?, last_success_at = ?, last_error = null where device_id = ?""",
                truncate(s.sysName(), 255), truncate(s.sysDescr(), 1024), truncate(s.sysLocation(), 255), s.uptimeTicks(), Timestamp.from(at), Timestamp.from(at), id);
        return readings;
    }

    private static BigInteger big(ResultSet rs, int col) throws SQLException {
        BigDecimal d = rs.getBigDecimal(col);
        return d == null ? null : d.toBigInteger();
    }

    private static BigDecimal dec(BigInteger v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
