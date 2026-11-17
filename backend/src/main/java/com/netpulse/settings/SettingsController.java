package com.netpulse.settings;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/settings")
@Tag(name = "Settings")
public class SettingsController {
    public record SettingsDto(
            @Positive(message = "Must be above 0") @Max(value = 60000, message = "At most 60000") double degradedRttMs,
            @Min(value = 0, message = "0-100") @Max(value = 100, message = "0-100") double degradedLossPct,
            @Min(value = 1, message = "At least 1") @Max(value = 100, message = "At most 100") int downAfterFailures,
            @Min(value = 1, message = "At least 1") @Max(value = 100, message = "At most 100") int upAfterSuccesses,
            @Min(value = 10, message = "At least 10 seconds") @Max(value = 3600, message = "At most 3600 seconds") int defaultIntervalSeconds,
            @Min(value = 1, message = "At least 1 day") @Max(value = 365, message = "At most 365 days") int rawDays,
            @Min(value = 1, message = "At least 1 day") @Max(value = 3650, message = "At most 3650 days") int rollupDays,
            @NotNull @Size(max = 64, message = "At most 64 ranges") List<@NotBlank String> scanCidrs) { }

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsDto get() {
        return toDto(service.current());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public SettingsDto update(@Valid @RequestBody SettingsDto d) {
        return toDto(service.update(new SettingsService.Values(d.degradedRttMs(), d.degradedLossPct(), d.downAfterFailures(),
                d.upAfterSuccesses(), d.defaultIntervalSeconds(), d.rawDays(), d.rollupDays(), d.scanCidrs())));
    }

    private static SettingsDto toDto(SettingsService.Values v) {
        return new SettingsDto(v.degradedRttMs(), v.degradedLossPct(), v.downAfterFailures(), v.upAfterSuccesses(),
                v.defaultIntervalSeconds(), v.rawDays(), v.rollupDays(), v.scanCidrs());
    }
}
