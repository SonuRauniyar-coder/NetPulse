package com.netpulse.event;

import com.netpulse.common.error.ApiException;
import com.netpulse.common.web.Csv;
import com.netpulse.device.DeviceStatus;
import com.netpulse.monitoring.result.MonitoringDtos.StatusEventDto;
import com.netpulse.monitoring.result.MonitoringQueryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The event log: every status transition, across all devices. */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Event log")
public class EventController {
    private static final int MAX_EXPORT = 100_000;
    private final JdbcTemplate jdbc;

    public EventController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Filter(String where, List<Object> args) { }

    private static Filter filter(UUID deviceId, DeviceStatus status, Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) throw ApiException.badRequest("'from' must be before 'to'");
        List<Object> args = new ArrayList<>();
        StringBuilder w = new StringBuilder(" where true");
        if (deviceId != null) { w.append(" and e.device_id = ?"); args.add(deviceId); }
        if (status != null) { w.append(" and e.to_status = ?"); args.add(status.name()); }
        if (from != null) { w.append(" and e.occurred_at >= ?"); args.add(Timestamp.from(from)); }
        if (to != null) { w.append(" and e.occurred_at < ?"); args.add(Timestamp.from(to)); }
        return new Filter(w.toString(), args);
    }

    @GetMapping
    public Page<StatusEventDto> list(@RequestParam(required = false) UUID deviceId, @RequestParam(required = false) DeviceStatus status,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                     @PageableDefault(size = 25) Pageable pageable) {
        Filter f = filter(deviceId, status, from, to);
        Long total = jdbc.queryForObject("select count(*) from status_event e" + f.where(), Long.class, f.args().toArray());
        List<Object> args = new ArrayList<>(f.args());
        args.add(pageable.getPageSize());
        args.add(pageable.getOffset());
        List<StatusEventDto> rows = jdbc.query(MonitoringQueryService.EVENT_SELECT + f.where() + " order by e.occurred_at desc, e.id desc limit ? offset ?",
                MonitoringQueryService.EVENT, args.toArray());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) UUID deviceId, @RequestParam(required = false) DeviceStatus status,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Filter f = filter(deviceId, status, from, to);
        List<Object> args = new ArrayList<>(f.args());
        args.add(MAX_EXPORT);
        List<String> rows = jdbc.query(MonitoringQueryService.EVENT_SELECT + f.where() + " order by e.occurred_at desc, e.id desc limit ?",
                (rs, n) -> {
                    StatusEventDto e = MonitoringQueryService.EVENT.mapRow(rs, n);
                    return Csv.row(e.occurredAt(), e.deviceName(), e.fromStatus(), e.toStatus(), e.reason());
                }, args.toArray());
        return Csv.download("netpulse-events.csv", Csv.row("occurred_at", "device", "from_status", "to_status", "reason"), rows);
    }
}
