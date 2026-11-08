package com.netpulse.device;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "device_port")
public class DevicePort {
    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id")
    private Device device;

    private int port;
    private String label;

    public DevicePort(Device device, int port, String label) {
        this.device = device;
        this.port = port;
        this.label = label;
    }

    protected DevicePort() { }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Device getDevice() { return device; }
    public void setDevice(Device device) { this.device = device; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
