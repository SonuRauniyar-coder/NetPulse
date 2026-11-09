package com.netpulse.snmp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netpulse.alert.AlertEngine;
import com.netpulse.alert.RuleEvaluator;
import com.netpulse.common.crypto.CredentialCipher;
import com.netpulse.common.net.InvalidTargetException;
import com.netpulse.common.net.TargetValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** One poll of one device. Network I/O happens outside any transaction, as for the other probes. */
@Service
public class SnmpPollService {
    private static final Logger log = LoggerFactory.getLogger(SnmpPollService.class);

    private final SnmpStore store;
    private final SnmpClient client;
    private final AlertEngine alerts;
    private final CredentialCipher cipher;
    private final TargetValidator validator;
    private final ObjectMapper mapper;
    private final Clock clock;

    public SnmpPollService(SnmpStore store, SnmpClient client, AlertEngine alerts, CredentialCipher cipher, TargetValidator validator, ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.client = client;
        this.alerts = alerts;
        this.cipher = cipher;
        this.validator = validator;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void poll(UUID deviceId) {
        var found = store.find(deviceId);
        if (found.isEmpty()) return;
        SnmpStore.Stored cfg = found.get();
        try {
            InetAddress address = validator.resolve(cfg.host());   // same DNS-rebinding guard as every other probe
            SnmpCredentials credentials = mapper.readValue(cipher.decrypt(cfg.credentials(), deviceId.toString()), SnmpCredentials.class);
            SnmpSnapshot snapshot = client.poll(address, cfg.port(), cfg.version(), credentials);
            List<SnmpStore.InterfaceReading> readings = store.recordSuccess(deviceId, snapshot, clock.instant());
            alerts.onSnmp(deviceId, new RuleEvaluator.SnmpFacts(cfg.deviceName(), readings));
        } catch (SnmpClient.SnmpPollException | InvalidTargetException e) {
            store.recordFailure(deviceId, e.getMessage(), clock.instant());
        } catch (UnknownHostException e) {
            store.recordFailure(deviceId, "Could not resolve " + cfg.host(), clock.instant());
        } catch (JsonProcessingException | IllegalStateException e) {
            log.error("Stored SNMP credentials for device {} are unusable: {}", deviceId, e.getMessage());
            store.recordFailure(deviceId, "Stored credentials could not be read. Check the encryption key or save them again.", clock.instant());
        }
    }
}
