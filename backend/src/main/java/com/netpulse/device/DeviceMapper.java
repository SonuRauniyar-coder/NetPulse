package com.netpulse.device;

import com.netpulse.device.dto.DeviceDtos.DeviceResponse;
import com.netpulse.device.dto.DeviceDtos.DnsCheckDto;
import com.netpulse.device.dto.DeviceDtos.PortDto;
import org.mapstruct.Mapper;

@Mapper
public interface DeviceMapper {
    DeviceResponse toResponse(Device device);
    PortDto toDto(DevicePort port);
    DnsCheckDto toDto(DeviceDnsCheck check);
}
