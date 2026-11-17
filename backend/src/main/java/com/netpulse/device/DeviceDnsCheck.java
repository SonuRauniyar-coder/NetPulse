package com.netpulse.device;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "device_dns_check")
public class DeviceDnsCheck {
    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id")
    private Device device;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_type", nullable = false, length = 8)
    private DnsRecordType recordType;

    private String queryName;   // null for PTR = reverse-lookup the device's own IP
    private String dnsServer;   // null = OS resolver

    public DeviceDnsCheck(Device device, DnsRecordType recordType, String queryName, String dnsServer) {
        this.device = device;
        this.recordType = recordType;
        this.queryName = queryName;
        this.dnsServer = dnsServer;
    }

    protected DeviceDnsCheck() { }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Device getDevice() { return device; }
    public void setDevice(Device device) { this.device = device; }
    public DnsRecordType getRecordType() { return recordType; }
    public void setRecordType(DnsRecordType recordType) { this.recordType = recordType; }
    public String getQueryName() { return queryName; }
    public void setQueryName(String queryName) { this.queryName = queryName; }
    public String getDnsServer() { return dnsServer; }
    public void setDnsServer(String dnsServer) { this.dnsServer = dnsServer; }
}
