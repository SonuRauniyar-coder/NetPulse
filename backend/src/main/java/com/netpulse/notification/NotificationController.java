package com.netpulse.notification;

import com.netpulse.alert.AlertTypes.Severity;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin only. The webhook URL is write-only: responses show its host and nothing else. */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
@PreAuthorize("hasRole('ADMIN')")
public class NotificationController {
    public record ChannelRequest(
            @NotBlank(message = "Name is required") @Size(max = 100) String name,
            @NotBlank(message = "Type is required") @Pattern(regexp = "EMAIL|WEBHOOK", message = "EMAIL or WEBHOOK") String type,
            boolean enabled,
            @NotNull(message = "Minimum severity is required") Severity minSeverity,
            @Size(max = 1000) String recipients,
            @Pattern(regexp = "SLACK|DISCORD|TELEGRAM|GENERIC", message = "Choose a format") String webhookFormat,
            @Size(max = 2048) String webhookUrl,
            @Size(max = 64) String telegramChatId) { }

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/channels")
    public List<NotificationService.ChannelDto> channels() {
        return service.channels();
    }

    @PostMapping("/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> create(@Valid @RequestBody ChannelRequest r) {
        return Map.of("id", service.create(input(r)));
    }

    @PutMapping("/channels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable UUID id, @Valid @RequestBody ChannelRequest r) {
        service.update(id, input(r));
    }

    @DeleteMapping("/channels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/channels/{id}/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void test(@PathVariable UUID id) {
        service.test(id);
    }

    @GetMapping("/deliveries")
    public List<NotificationService.DeliveryDto> deliveries() {
        return service.recentDeliveries();
    }

    private static NotificationService.ChannelInput input(ChannelRequest r) {
        return new NotificationService.ChannelInput(r.name(), r.type(), r.enabled(), r.minSeverity(), r.recipients(), r.webhookFormat(), r.webhookUrl(), r.telegramChatId());
    }
}
