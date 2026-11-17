package com.netpulse.monitoring.engine;

import com.netpulse.device.Device;
import com.netpulse.device.DevicePort;
import com.netpulse.device.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class DeviceSnapshotLoader {
    private final DeviceRepository repo;

    public DeviceSnapshotLoader(DeviceRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Optional<DeviceSnapshot> load(UUID id) {
        return repo.findById(id).filter(Device::isEnabled).map(d -> new DeviceSnapshot(d.getId(), d.getName(), d.getHost(),
                d.getPorts().stream().map(DevicePort::getPort).sorted().toList(),
                d.getDnsChecks().stream().map(c -> new DeviceSnapshot.DnsSpec(c.getRecordType(), c.getQueryName(), c.getDnsServer())).toList()));
    }
}
