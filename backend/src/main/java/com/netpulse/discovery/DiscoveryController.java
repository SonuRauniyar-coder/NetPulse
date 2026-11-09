package com.netpulse.discovery;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/discovery/scans")
@Tag(name = "Discovery")
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class DiscoveryController {
    public record ScanRequest(@NotBlank(message = "Network is required") @Size(max = 18) String cidr) { }

    private final DiscoveryService service;

    public DiscoveryController(DiscoveryService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DiscoveryService.ScanDto start(@Valid @RequestBody ScanRequest req) {
        return service.start(req.cidr());
    }

    @GetMapping
    public Page<DiscoveryService.ScanDto> list(@PageableDefault(size = 10) Pageable pageable) {
        return service.list(pageable);
    }

    @GetMapping("/{id}")
    public DiscoveryService.ScanDetail get(@PathVariable UUID id) {
        return service.get(id);
    }
}
