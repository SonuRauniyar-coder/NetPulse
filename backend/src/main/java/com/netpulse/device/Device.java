package com.netpulse.device;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "device")
public class Device {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 253)
    private String host;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeviceType type;

    private String location;
    private String description;

    @Column(name = "check_interval_seconds", nullable = false)
    private int checkIntervalSeconds;

    private boolean enabled = true;

    // Monitoring state: written by the engine with SQL; read-only for the CRUD path except pause/resume.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeviceStatus status = DeviceStatus.UNKNOWN;

    private boolean icmpBlocked;
    private int consecutiveFailures;
    private int consecutiveSuccesses;
    private String resolvedIp;
    private Double lastRttMs;
    private Double lastLossPct;
    private Instant lastCheckedAt;
    private Instant nextCheckAt;
    private Instant statusChangedAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    @ElementCollection
    @CollectionTable(name = "device_tag", joinColumns = @JoinColumn(name = "device_id"))
    @Column(name = "tag")
    @BatchSize(size = 100)   // avoids N+1 selects when mapping a page of devices
    private Set<String> tags = new HashSet<>();

    @OneToMany(mappedBy = "device", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 100)
    private List<DevicePort> ports = new ArrayList<>();

    @OneToMany(mappedBy = "device", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 100)
    private List<DeviceDnsCheck> dnsChecks = new ArrayList<>();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    /** Clears observed state; used when a device is created, resumed or re-pointed at a different host. */
    public void resetMonitoringState(DeviceStatus newStatus, Instant nextCheck) {
        status = newStatus;
        icmpBlocked = false;
        consecutiveFailures = 0;
        consecutiveSuccesses = 0;
        resolvedIp = null;
        lastRttMs = null;
        lastLossPct = null;
        nextCheckAt = nextCheck;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public DeviceType getType() { return type; }
    public void setType(DeviceType type) { this.type = type; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getCheckIntervalSeconds() { return checkIntervalSeconds; }
    public void setCheckIntervalSeconds(int checkIntervalSeconds) { this.checkIntervalSeconds = checkIntervalSeconds; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public DeviceStatus getStatus() { return status; }
    public void setStatus(DeviceStatus status) { this.status = status; }
    public boolean isIcmpBlocked() { return icmpBlocked; }
    public void setIcmpBlocked(boolean icmpBlocked) { this.icmpBlocked = icmpBlocked; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public int getConsecutiveSuccesses() { return consecutiveSuccesses; }
    public void setConsecutiveSuccesses(int consecutiveSuccesses) { this.consecutiveSuccesses = consecutiveSuccesses; }
    public String getResolvedIp() { return resolvedIp; }
    public void setResolvedIp(String resolvedIp) { this.resolvedIp = resolvedIp; }
    public Double getLastRttMs() { return lastRttMs; }
    public void setLastRttMs(Double lastRttMs) { this.lastRttMs = lastRttMs; }
    public Double getLastLossPct() { return lastLossPct; }
    public void setLastLossPct(Double lastLossPct) { this.lastLossPct = lastLossPct; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public Instant getNextCheckAt() { return nextCheckAt; }
    public void setNextCheckAt(Instant nextCheckAt) { this.nextCheckAt = nextCheckAt; }
    public Instant getStatusChangedAt() { return statusChangedAt; }
    public void setStatusChangedAt(Instant statusChangedAt) { this.statusChangedAt = statusChangedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public Set<String> getTags() { return tags; }
    public void setTags(Set<String> tags) { this.tags = tags; }
    public List<DevicePort> getPorts() { return ports; }
    public void setPorts(List<DevicePort> ports) { this.ports = ports; }
    public List<DeviceDnsCheck> getDnsChecks() { return dnsChecks; }
    public void setDnsChecks(List<DeviceDnsCheck> dnsChecks) { this.dnsChecks = dnsChecks; }
}
