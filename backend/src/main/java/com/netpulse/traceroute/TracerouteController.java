package com.netpulse.traceroute;

import com.netpulse.common.error.ApiException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/traceroute")
@Tag(name = "Traceroute")
@PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
public class TracerouteController {
    public record TraceRequest(@NotBlank(message = "IP or hostname is required") @Size(max = 253) String target) { }

    private final TracerouteService service;

    public TracerouteController(TracerouteService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TracerouteService.Run start(@Valid @RequestBody TraceRequest req) {
        return service.start(req.target());
    }

    @GetMapping("/{id}")
    public TracerouteService.Run get(@PathVariable UUID id) {
        return service.find(id).orElseThrow(() -> ApiException.notFound("Traceroute run"));
    }
}
