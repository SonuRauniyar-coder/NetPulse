package com.netpulse.device.dto;

import com.netpulse.device.DeviceStatus;
import com.netpulse.device.DeviceType;
import com.netpulse.device.DnsRecordType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class DeviceDtos {
    private DeviceDtos() { }

    public record PortDto(
            @Min(value = 1, message = "Port must be 1-65535") @Max(value = 65535, message = "Port must be 1-65535") int port,
            @Size(max = 32) String label) { }

    public record DnsCheckDto(
            @NotNull DnsRecordType recordType,
            @Size(max = 253) String queryName,
            @Size(max = 253) String dnsServer) { }

    public record DeviceRequest(
            @NotBlank(message = "Name is required") @Size(max = 100) String name,
            @NotBlank(message = "IP or hostname is required") @Size(max = 253) String host,
            @NotNull(message = "Type is required") DeviceType type,
            @Size(max = 100) String location,
            @Size(max = 500) String description,
            @Size(max = 10, message = "At most 10 tags") Set<@Pattern(regexp = "[A-Za-z0-9._-]{1,32}", message = "Tags: letters, digits, . _ - (max 32)") String> tags,
            @Min(value = 10, message = "Interval must be at least 10 seconds") @Max(value = 3600, message = "Interval must be at most 3600 seconds") Integer checkIntervalSeconds,
            Boolean enabled,
            @Size(max = 32, message = "At most 32 ports") List<@Valid PortDto> ports,
            @Size(max = 16, message = "At most 16 DNS checks") List<@Valid DnsCheckDto> dnsChecks) { }

    public record DeviceResponse(
            UUID id, String name, String host, DeviceType type, String location, String description,
            Set<String> tags, int checkIntervalSeconds, boolean enabled,
            DeviceStatus status, boolean icmpBlocked, String resolvedIp, Double lastRttMs, Double lastLossPct,
            Instant lastCheckedAt, Instant nextCheckAt, Instant statusChangedAt, Instant createdAt, Instant updatedAt,
            List<PortDto> ports, List<DnsCheckDto> dnsChecks) { }

    public enum BulkAction { PAUSE, RESUME, DELETE }

    public record BulkRequest(
            @NotEmpty(message = "Select at least one device") @Size(max = 500) List<UUID> ids,
            @NotNull BulkAction action) { }

    public record BulkResponse(int affected) { }
}
