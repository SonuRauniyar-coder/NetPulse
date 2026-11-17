package com.netpulse.snmp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netpulse.audit.AuditService;
import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.crypto.CredentialCipher;
import com.netpulse.common.error.ApiException;
import com.netpulse.device.DeviceRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Per-device SNMP settings and readings. Secrets are write-only: no response contains any part of them. */
@RestController
@RequestMapping("/api/v1/devices/{id}/snmp")
@Tag(name = "SNMP")
public class SnmpController {
    private static final String CAN_WRITE = "hasAnyRole('ADMIN','OPERATOR')";
    private static final int SPARK_POINTS = 60;

    public record ConfigRequest(
            boolean enabled,
            @NotNull(message = "Version is required") SnmpVersion version,
            @Min(value = 1, message = "1-65535") @Max(value = 65535, message = "1-65535") Integer port,
            @Min(value = 10, message = "At least 10 seconds") @Max(value = 3600, message = "At most 3600 seconds") Integer intervalSeconds,
            @Size(max = 64) String community,
            @Size(max = 32) String username,
            @Pattern(regexp = "SHA|SHA256", message = "Use SHA or SHA256") String authProtocol,
            @Size(max = 64) String authPassword,
            @Size(max = 64) String privPassword) { }

    public record ConfigDto(boolean enabled, SnmpVersion version, int port, int intervalSeconds, String sysName, String sysDescr, String sysLocation,
                            Long sysUptimeTicks, Instant lastPollAt, Instant lastSuccessAt, String lastError) { }

    public record Spark(Instant t, double inBps, double outBps) { }

    public record InterfaceDto(int ifIndex, String descr, Integer adminStatus, Integer operStatus, Long speedBps, Double inBps, Double outBps,
                               Double utilizationPct, Long inErrors, Long outErrors, Integer counterBits, List<Spark> samples) { }

    public record SnmpView(ConfigDto config, List<InterfaceDto> interfaces) { }

    private final JdbcTemplate jdbc;
    private final SnmpStore store;
    private final CredentialCipher cipher;
    private final DeviceRepository devices;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final NetPulseProperties props;
    private final Clock clock;

    public SnmpController(JdbcTemplate jdbc, SnmpStore store, CredentialCipher cipher, DeviceRepository devices, AuditService audit,
                          ObjectMapper mapper, NetPulseProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.cipher = cipher;
        this.devices = devices;
        this.audit = audit;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping
    public SnmpView get(@PathVariable UUID id) {
        requireDevice(id);
        ConfigDto config = jdbc.query("""
                select enabled, version, port, interval_seconds, sys_name, sys_descr, sys_location, sys_uptime_ticks, last_poll_at, last_success_at, last_error
                from snmp_config where device_id = ?""", (rs, n) -> {
            long ticks = rs.getLong(8);
            Long uptime = rs.wasNull() ? null : ticks;
            return new ConfigDto(rs.getBoolean(1), SnmpVersion.valueOf(rs.getString(2)), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6),
                    rs.getString(7), uptime, instant(rs.getTimestamp(9)), instant(rs.getTimestamp(10)), rs.getString(11));
        }, id).stream().findFirst().orElse(null);
        return new SnmpView(config, config == null ? List.of() : interfaces(id));
    }

    private List<InterfaceDto> interfaces(UUID id) {
        Map<Integer, List<Spark>> sparks = new HashMap<>();
        jdbc.query("""
                select if_index, sampled_at, in_bps, out_bps from (
                    select if_index, sampled_at, in_bps, out_bps, row_number() over (partition by if_index order by sampled_at desc) rn
                    from snmp_sample where device_id = ?) s
                where rn <= ? order by sampled_at""", rs -> {
            sparks.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>())
                    .add(new Spark(rs.getTimestamp(2).toInstant(), rs.getDouble(3), rs.getDouble(4)));
        }, id, SPARK_POINTS);
        return jdbc.query("""
                select if_index, if_descr, admin_status, oper_status, speed_bps, in_bps, out_bps, utilization_pct, in_errors, out_errors, counter_bits
                from snmp_interface where device_id = ? order by if_index""",
                (rs, n) -> new InterfaceDto(rs.getInt(1), rs.getString(2), rs.getObject(3, Integer.class), rs.getObject(4, Integer.class), rs.getObject(5, Long.class),
                        rs.getObject(6, Double.class), rs.getObject(7, Double.class), rs.getObject(8, Double.class),
                        rs.getBigDecimal(9) == null ? null : rs.getBigDecimal(9).longValue(), rs.getBigDecimal(10) == null ? null : rs.getBigDecimal(10).longValue(),
                        rs.getObject(11, Integer.class), sparks.getOrDefault(rs.getInt(1), List.of())), id);
    }

    @PutMapping
    @PreAuthorize(CAN_WRITE)
    @Transactional
    public SnmpView save(@PathVariable UUID id, @Valid @RequestBody ConfigRequest r) {
        requireDevice(id);
        if (props.demoMode()) throw ApiException.conflict("SNMP polling doesn't run in demo mode.");
        Optional<SnmpStore.Stored> existing = store.find(id);
        String credentials = resolveCredentials(id, r, existing);
        int port = r.port() == null ? 161 : r.port();
        int interval = r.intervalSeconds() == null ? props.snmp().defaultIntervalSeconds() : r.intervalSeconds();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into snmp_config (device_id, enabled, version, port, interval_seconds, credentials, next_poll_at) values (?, ?, ?, ?, ?, ?, ?)
                on conflict (device_id) do update set enabled = excluded.enabled, version = excluded.version, port = excluded.port,
                    interval_seconds = excluded.interval_seconds, credentials = excluded.credentials, next_poll_at = excluded.next_poll_at""",
                id, r.enabled(), r.version().name(), port, interval, credentials, now);
        audit.record("SNMP_CONFIG_SAVE", "device", id, "SNMP " + r.version() + " on port " + port + (r.enabled() ? " enabled" : " disabled"));
        return get(id);
    }

    /** A blank secret on an update means "keep what is stored", as long as the version hasn't changed. */
    private String resolveCredentials(UUID id, ConfigRequest r, Optional<SnmpStore.Stored> existing) {
        boolean noSecrets = isBlank(r.community()) && isBlank(r.username()) && isBlank(r.authPassword()) && isBlank(r.privPassword());
        if (noSecrets && existing.isPresent() && existing.get().version() == r.version()) return existing.get().credentials();
        SnmpCredentials c;
        if (r.version() == SnmpVersion.V2C) {
            if (isBlank(r.community())) throw ApiException.badRequest("A community string is required for SNMP v2c");
            c = new SnmpCredentials(r.community(), null, null, null, null);
        } else {
            if (isBlank(r.username())) throw ApiException.badRequest("A username is required for SNMPv3");
            // RFC 3414 localizes keys from the passphrase and requires at least 8 characters.
            if (r.authPassword() == null || r.authPassword().length() < 8) throw ApiException.badRequest("The authentication passphrase needs at least 8 characters");
            if (r.privPassword() == null || r.privPassword().length() < 8) throw ApiException.badRequest("The privacy passphrase needs at least 8 characters");
            c = new SnmpCredentials(null, r.username(), r.authProtocol() == null ? "SHA" : r.authProtocol(), r.authPassword(), r.privPassword());
        }
        try {
            return cipher.encrypt(mapper.writeValueAsString(c), id.toString());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize credentials");
        }
    }

    @DeleteMapping
    @PreAuthorize(CAN_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id) {
        requireDevice(id);
        jdbc.update("delete from snmp_interface where device_id = ?", id);
        if (jdbc.update("delete from snmp_config where device_id = ?", id) > 0) audit.record("SNMP_CONFIG_DELETE", "device", id, "SNMP removed");
    }

    @PostMapping("/poll")
    @PreAuthorize(CAN_WRITE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void pollNow(@PathVariable UUID id) {
        if (store.find(id).isEmpty()) throw ApiException.notFound("SNMP configuration");
        store.scheduleNext(id, clock.instant());
    }

    private void requireDevice(UUID id) {
        if (!devices.existsById(id)) throw ApiException.notFound("Device");
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
