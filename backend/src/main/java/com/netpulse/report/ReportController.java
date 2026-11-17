package com.netpulse.report;

import com.netpulse.common.web.Csv;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports")
public class ReportController {
    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/uptime")
    public ReportService.Report uptime(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                       @RequestParam(defaultValue = "99.9") double slaTarget,
                                       @RequestParam(required = false) UUID deviceId) {
        return service.uptime(from, to, slaTarget, deviceId);
    }

    @GetMapping("/uptime/export.csv")
    public ResponseEntity<byte[]> export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                         @RequestParam(defaultValue = "99.9") double slaTarget,
                                         @RequestParam(required = false) UUID deviceId) {
        var report = service.uptime(from, to, slaTarget, deviceId);
        return Csv.download("netpulse-uptime.csv",
                Csv.row("device", "host", "uptime_pct", "observed_checks", "up", "degraded", "down", "observed_seconds", "downtime_seconds", "incidents", "sla_target_pct", "sla_met"),
                report.devices().stream().map(d -> Csv.row(d.name(), d.host(),
                        d.uptimePct() == null ? null : String.format(Locale.ROOT, "%.4f", d.uptimePct()), d.observedChecks(), d.up(), d.degraded(), d.down(),
                        d.observedSeconds(), d.downtimeSeconds(), d.incidents(), report.slaTargetPct(), d.slaMet())).toList());
    }
}
