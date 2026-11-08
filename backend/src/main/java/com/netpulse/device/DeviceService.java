package com.netpulse.device;

import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.error.ApiException;
import com.netpulse.common.net.InvalidTargetException;
import com.netpulse.common.net.IpLiterals;
import com.netpulse.common.net.TargetValidator;
import com.netpulse.device.dto.DeviceDtos.*;
import com.netpulse.monitoring.result.CheckResultStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DeviceService {
    private static final Set<String> SORTABLE = Set.of("name", "host", "type", "status", "location",
            "lastRttMs", "lastLossPct", "lastCheckedAt", "createdAt");

    private final DeviceRepository repo;
    private final DeviceMapper mapper;
    private final TargetValidator validator;
    private final CheckResultStore results;
    private final NetPulseProperties props;
    private final Clock clock;

    public DeviceService(DeviceRepository repo, DeviceMapper mapper, TargetValidator validator,
                         CheckResultStore results, NetPulseProperties props, Clock clock) {
        this.repo = repo;
        this.mapper = mapper;
        this.validator = validator;
        this.results = results;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<DeviceResponse> list(DeviceStatus status, DeviceType type, String tag, String q, Pageable pageable) {
        for (Sort.Order o : pageable.getSort()) {
            if (!SORTABLE.contains(o.getProperty())) throw ApiException.badRequest("Cannot sort by '" + o.getProperty() + "'");
        }
        Pageable effective = pageable.getSort().isSorted() ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("name"));
        Specification<Device> spec = Specification.allOf(
                DeviceSpecifications.hasStatus(status), DeviceSpecifications.hasType(type),
                DeviceSpecifications.hasTag(tag), DeviceSpecifications.search(q));
        return repo.findAll(spec, effective).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public DeviceResponse get(UUID id) {
        return mapper.toResponse(load(id));
    }

    @Transactional(readOnly = true)
    public List<String> tags() {
        return repo.findAllTags(PageRequest.of(0, 200));
    }

    @Transactional
    public DeviceResponse create(DeviceRequest req) {
        String name = req.name().trim();
        if (repo.existsByNameIgnoreCase(name)) throw duplicateName(name);
        Device d = new Device();
        applyEditableFields(d, req, name);
        Instant now = clock.instant();
        boolean enabled = req.enabled() == null || req.enabled();
        d.setEnabled(enabled);
        d.resetMonitoringState(enabled ? DeviceStatus.UNKNOWN : DeviceStatus.PAUSED, enabled ? now : null);
        d.setStatusChangedAt(now);
        return mapper.toResponse(repo.save(d));
    }

    @Transactional
    public DeviceResponse update(UUID id, DeviceRequest req) {
        Device d = load(id);
        String name = req.name().trim();
        if (repo.existsByNameIgnoreCaseAndIdNot(name, id)) throw duplicateName(name);
        String oldHost = d.getHost();
        applyEditableFields(d, req, name);
        Instant now = clock.instant();

        boolean wantEnabled = req.enabled() == null ? d.isEnabled() : req.enabled();
        if (wantEnabled != d.isEnabled()) {
            setEnabled(d, wantEnabled, now);
        } else if (!oldHost.equals(d.getHost()) && d.isEnabled()) {
            // A different host means previous observations no longer apply.
            transition(d, DeviceStatus.UNKNOWN, "Host changed", now);
            d.resetMonitoringState(DeviceStatus.UNKNOWN, now);
        } else if (d.isEnabled() && d.getNextCheckAt() != null) {
            Instant soonest = now.plusSeconds(d.getCheckIntervalSeconds());
            if (d.getNextCheckAt().isAfter(soonest)) d.setNextCheckAt(soonest); // a shortened interval applies now
        }
        return mapper.toResponse(d);
    }

    @Transactional
    public void delete(UUID id) {
        repo.delete(load(id));
    }

    @Transactional
    public DeviceResponse pause(UUID id) {
        Device d = load(id);
        if (d.isEnabled()) setEnabled(d, false, clock.instant());
        return mapper.toResponse(d);
    }

    @Transactional
    public DeviceResponse resume(UUID id) {
        Device d = load(id);
        if (!d.isEnabled()) setEnabled(d, true, clock.instant());
        return mapper.toResponse(d);
    }

    /** Makes the device due now; the scheduler picks it up on its next tick (at most ~1s later). */
    @Transactional
    public DeviceResponse checkNow(UUID id) {
        Device d = load(id);
        if (!d.isEnabled()) throw ApiException.conflict("Device is paused. Resume it to run checks.");
        d.setNextCheckAt(clock.instant());
        return mapper.toResponse(d);
    }

    @Transactional
    public BulkResponse bulk(BulkRequest req) {
        List<Device> devices = repo.findAllById(new LinkedHashSet<>(req.ids()));
        Instant now = clock.instant();
        switch (req.action()) {
            case DELETE -> repo.deleteAll(devices);
            case PAUSE -> devices.stream().filter(Device::isEnabled).forEach(d -> setEnabled(d, false, now));
            case RESUME -> devices.stream().filter(d -> !d.isEnabled()).forEach(d -> setEnabled(d, true, now));
        }
        return new BulkResponse(devices.size());
    }

    private void setEnabled(Device d, boolean enabled, Instant now) {
        d.setEnabled(enabled);
        DeviceStatus next = enabled ? DeviceStatus.UNKNOWN : DeviceStatus.PAUSED;
        transition(d, next, enabled ? "Monitoring resumed" : "Monitoring paused", now);
        d.resetMonitoringState(next, enabled ? now : null);
    }

    private void transition(Device d, DeviceStatus to, String reason, Instant now) {
        if (d.getStatus() != to) {
            results.insertStatusEvent(d.getId(), d.getStatus(), to, reason, now);
            d.setStatusChangedAt(now);
        }
    }

    private void applyEditableFields(Device d, DeviceRequest req, String name) {
        d.setName(name);
        d.setHost(validator.validateHost(req.host()));
        d.setType(req.type());
        d.setLocation(blankToNull(req.location()));
        d.setDescription(blankToNull(req.description()));
        d.setCheckIntervalSeconds(req.checkIntervalSeconds() != null ? req.checkIntervalSeconds()
                : props.monitoring().defaultIntervalSeconds());

        d.getTags().clear();
        if (req.tags() != null) {
            d.getTags().addAll(req.tags().stream().map(t -> t.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet()));
        }
        syncPorts(d, req.ports() == null ? List.of() : req.ports());
        syncDnsChecks(d, req.dnsChecks() == null ? List.of() : req.dnsChecks());
    }

    /** Updates ports in place: removing and re-adding the same port in one flush would break UNIQUE(device_id, port). */
    private static void syncPorts(Device d, List<PortDto> wanted) {
        Map<Integer, String> byPort = new LinkedHashMap<>();
        for (PortDto p : wanted) {
            if (byPort.containsKey(p.port())) throw ApiException.badRequest("Port " + p.port() + " is listed twice");
            byPort.put(p.port(), blankToNull(p.label()));
        }
        d.getPorts().removeIf(p -> !byPort.containsKey(p.getPort()));
        for (DevicePort existing : d.getPorts()) {
            existing.setLabel(byPort.remove(existing.getPort()));
        }
        byPort.forEach((port, label) -> d.getPorts().add(new DevicePort(d, port, label)));
    }

    private void syncDnsChecks(Device d, List<DnsCheckDto> wanted) {
        d.getDnsChecks().clear();
        for (DnsCheckDto c : wanted) {
            d.getDnsChecks().add(new DeviceDnsCheck(d, c.recordType(), validateQueryName(c), validateServer(c.dnsServer())));
        }
    }

    private String validateQueryName(DnsCheckDto c) {
        String q = blankToNull(c.queryName());
        if (c.recordType() == DnsRecordType.PTR) {
            if (q != null && !IpLiterals.isIpLiteral(q)) throw new InvalidTargetException("PTR query must be an IP address (or empty for the device's own IP)");
            return q;
        }
        if (q == null) throw new InvalidTargetException(c.recordType() + " check needs a domain name to query");
        if (!TargetValidator.isValidHostname(q)) throw new InvalidTargetException("'" + q + "' is not a valid domain name");
        return q.toLowerCase(Locale.ROOT);
    }

    private String validateServer(String raw) {
        String s = blankToNull(raw);
        return s == null ? null : validator.validateHost(s);
    }

    private Device load(UUID id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Device"));
    }

    private static ApiException duplicateName(String name) {
        return ApiException.conflict("A device named '" + name + "' already exists");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
