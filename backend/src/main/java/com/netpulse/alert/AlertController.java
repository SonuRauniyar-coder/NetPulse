package com.netpulse.alert;

import com.netpulse.alert.AlertService.*;
import com.netpulse.alert.AlertTypes.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Alerts")
public class AlertController {
    private static final String ADMIN = "hasRole('ADMIN')";
    private static final String CAN_OPERATE = "hasAnyRole('ADMIN','OPERATOR')";

    public record RuleRequest(
            @NotBlank(message = "Name is required") @Size(max = 100) String name,
            @NotNull(message = "Type is required") RuleType type,
            @NotNull(message = "Severity is required") Severity severity,
            UUID deviceId,
            Double threshold,
            @Min(value = 1, message = "1-65535") @Max(value = 65535, message = "1-65535") Integer port,
            @Size(max = 255) String interfaceName,
            @Min(value = 0, message = "Can't be negative") @Max(value = 86400, message = "At most 24 hours") int durationSeconds,
            boolean enabled) { }

    public record WindowRequest(@NotNull(message = "Device is required") UUID deviceId,
                                @NotNull(message = "Start is required") Instant startsAt,
                                @NotNull(message = "End is required") Instant endsAt,
                                @Size(max = 255) String reason) { }

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    @GetMapping("/alerts")
    public Page<AlertDto> list(@RequestParam(required = false) State state, @RequestParam(required = false) UUID deviceId,
                               @PageableDefault(size = 25) Pageable pageable) {
        return service.list(state, deviceId, pageable);
    }

    @GetMapping("/alerts/summary")
    public Map<String, Long> summary() {
        Summary s = service.summary();
        return Map.of("active", s.active(), "triggered", s.triggered(), "acknowledged", s.acknowledged(), "critical", s.critical());
    }

    @PostMapping("/alerts/{id}/acknowledge")
    @PreAuthorize(CAN_OPERATE)
    public AlertDto acknowledge(@PathVariable UUID id) {
        return service.acknowledge(id);
    }

    @GetMapping("/alert-rules")
    public List<RuleDto> rules() {
        return service.rules();
    }

    @PostMapping("/alert-rules")
    @PreAuthorize(ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> createRule(@Valid @RequestBody RuleRequest r) {
        return Map.of("id", service.createRule(input(r)));
    }

    @PutMapping("/alert-rules/{id}")
    @PreAuthorize(ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateRule(@PathVariable UUID id, @Valid @RequestBody RuleRequest r) {
        service.updateRule(id, input(r));
    }

    @DeleteMapping("/alert-rules/{id}")
    @PreAuthorize(ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID id) {
        service.deleteRule(id);
    }

    @GetMapping("/maintenance-windows")
    public List<WindowDto> windows() {
        return service.windows();
    }

    @PostMapping("/maintenance-windows")
    @PreAuthorize(CAN_OPERATE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> createWindow(@Valid @RequestBody WindowRequest r) {
        return Map.of("id", service.createWindow(r.deviceId(), r.startsAt(), r.endsAt(), r.reason()));
    }

    @DeleteMapping("/maintenance-windows/{id}")
    @PreAuthorize(CAN_OPERATE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWindow(@PathVariable UUID id) {
        service.deleteWindow(id);
    }

    private static RuleInput input(RuleRequest r) {
        return new RuleInput(r.name(), r.type(), r.severity(), r.deviceId(), r.threshold(), r.port(), r.interfaceName(), r.durationSeconds(), r.enabled());
    }
}
