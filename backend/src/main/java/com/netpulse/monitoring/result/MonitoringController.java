package com.netpulse.monitoring.result;

import com.netpulse.monitoring.result.MonitoringDtos.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices/{id}")
@Tag(name = "Monitoring results")
public class MonitoringController {
    private final MonitoringQueryService service;

    public MonitoringController(MonitoringQueryService service) {
        this.service = service;
    }

    @GetMapping("/metrics")
    public List<MetricPoint> metrics(@PathVariable UUID id, @RequestParam(defaultValue = "1h") String range) {
        return service.metrics(id, MetricRange.parse(range));
    }

    @GetMapping("/checks")
    public Page<CheckSummary> checks(@PathVariable UUID id, @PageableDefault(size = 25) Pageable pageable) {
        return service.checks(id, pageable);
    }

    @GetMapping("/checks/latest")
    public ResponseEntity<CheckDetail> latest(@PathVariable UUID id) {
        return service.latest(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/uptime")
    public UptimeResponse uptime(@PathVariable UUID id) {
        return service.uptime(id);
    }

    @GetMapping("/events")
    public Page<StatusEventDto> events(@PathVariable UUID id, @PageableDefault(size = 25) Pageable pageable) {
        return service.events(id, pageable);
    }
}
