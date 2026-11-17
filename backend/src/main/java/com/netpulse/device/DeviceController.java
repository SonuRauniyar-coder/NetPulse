package com.netpulse.device;

import com.netpulse.device.dto.DeviceDtos.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices")
@Tag(name = "Devices")
public class DeviceController {
    private static final String CAN_WRITE = "hasAnyRole('ADMIN','OPERATOR')";
    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @GetMapping
    public Page<DeviceResponse> list(@RequestParam(required = false) DeviceStatus status,
                                     @RequestParam(required = false) DeviceType type,
                                     @RequestParam(required = false) String tag,
                                     @RequestParam(required = false) String q,
                                     @PageableDefault(size = 25) Pageable pageable) {
        return service.list(status, type, tag, q, pageable);
    }

    @GetMapping("/tags")
    public List<String> tags() {
        return service.tags();
    }

    @GetMapping("/{id}")
    public DeviceResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CAN_WRITE)
    public DeviceResponse create(@Valid @RequestBody DeviceRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize(CAN_WRITE)
    public DeviceResponse update(@PathVariable UUID id, @Valid @RequestBody DeviceRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(CAN_WRITE)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize(CAN_WRITE)
    public DeviceResponse pause(@PathVariable UUID id) {
        return service.pause(id);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize(CAN_WRITE)
    public DeviceResponse resume(@PathVariable UUID id) {
        return service.resume(id);
    }

    @PostMapping("/{id}/check-now")
    @PreAuthorize(CAN_WRITE)
    public DeviceResponse checkNow(@PathVariable UUID id) {
        return service.checkNow(id);
    }

    @PostMapping("/bulk")
    @PreAuthorize(CAN_WRITE)
    public BulkResponse bulk(@Valid @RequestBody BulkRequest req) {
        return service.bulk(req);
    }
}
