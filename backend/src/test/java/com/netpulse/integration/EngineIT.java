package com.netpulse.integration;

import com.netpulse.device.Device;
import com.netpulse.device.DevicePort;
import com.netpulse.device.DeviceRepository;
import com.netpulse.device.DeviceStatus;
import com.netpulse.device.DeviceType;
import com.netpulse.monitoring.engine.CheckRunner;
import com.netpulse.monitoring.retention.RollupService;
import com.netpulse.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End to end against the real loopback interface: real ping process, real sockets, real database. */
class EngineIT extends IntegrationTestBase {
    @Autowired CheckRunner runner;
    @Autowired DeviceRepository devices;
    @Autowired RollupService rollups;
    @Autowired MockMvc mvc;

    @BeforeEach void clean() {
        jdbc.update("delete from device");
    }

    private Device device(String host, List<Integer> ports) {
        Device d = new Device();
        d.setName("dev-" + UUID.randomUUID());
        d.setHost(host);
        d.setType(DeviceType.SERVER);
        d.setCheckIntervalSeconds(60);
        ports.forEach(p -> d.getPorts().add(new DevicePort(d, p, null)));
        return devices.saveAndFlush(d);
    }

    private static int closedPort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    @Test void fullCycleAgainstLoopback() throws Exception {
        try (ServerSocket open = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            int closed = closedPort();
            Device d = device("127.0.0.1", List.of(open.getLocalPort(), closed));
            runner.run(d.getId());

            Device after = devices.findById(d.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(DeviceStatus.DEGRADED);  // reachable, but one configured port is closed
            assertThat(after.getResolvedIp()).isEqualTo("127.0.0.1");
            assertThat(after.getLastRttMs()).isNotNull();
            assertThat(after.getLastLossPct()).isZero();

            assertThat(jdbc.queryForObject("""
                    select p.packets_received from ping_result p join device_check c on c.id = p.check_id where c.device_id = ?""",
                    Integer.class, d.getId())).isEqualTo(4);
            assertThat(jdbc.queryForList("""
                    select t.state from tcp_result t join device_check c on c.id = t.check_id where c.device_id = ? order by t.state""",
                    String.class, d.getId())).containsExactly("CLOSED", "OPEN");
            assertThat(jdbc.queryForObject("select count(*) from status_event where device_id = ?", Integer.class, d.getId())).isEqualTo(1);

            String admin = tokenFor(Role.ADMIN);
            mvc.perform(get("/api/v1/devices/" + d.getId() + "/checks/latest").header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.check.ping.received").value(4))
                    .andExpect(jsonPath("$.tcp", hasSize(2)));
            mvc.perform(get("/api/v1/devices/" + d.getId() + "/metrics").header("Authorization", admin).param("range", "1h"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
            mvc.perform(get("/api/v1/devices/" + d.getId() + "/uptime").header("Authorization", admin))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.uptimePct").value(100.0)).andExpect(jsonPath("$.segments", hasSize(48)));
            mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", admin))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.degraded").value(1));
            mvc.perform(get("/api/v1/dashboard/latency").header("Authorization", admin).param("range", "6h"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Test void unresolvableHostIsDnsFailureAndGoesDownOnlyAtThreshold() {
        Device d = device("nonexistent.invalid", List.of());
        runner.run(d.getId());
        assertThat(devices.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DeviceStatus.UNKNOWN);
        assertThat(jdbc.queryForObject("""
                select p.error_category from ping_result p join device_check c on c.id = p.check_id where c.device_id = ?""",
                String.class, d.getId())).isEqualTo("DNS_FAILURE");
        runner.run(d.getId());
        runner.run(d.getId());
        assertThat(devices.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(DeviceStatus.DOWN);
    }

    @Test void pausedDeviceIsNotChecked() {
        Device d = device("127.0.0.1", List.of());
        d.setEnabled(false);
        devices.saveAndFlush(d);
        runner.run(d.getId());
        assertThat(jdbc.queryForObject("select count(*) from device_check where device_id = ?", Integer.class, d.getId())).isZero();
    }

    @Test void rollupIsIdempotent() {
        Device d = device("127.0.0.1", List.of());
        runner.run(d.getId());
        Instant now = Instant.now();
        rollups.rollup(now.minus(1, ChronoUnit.HOURS), now.plusSeconds(1));
        rollups.rollup(now.minus(1, ChronoUnit.HOURS), now.plusSeconds(1));
        assertThat(jdbc.queryForObject("select sum(checks) from rollup_hourly where device_id = ?", Integer.class, d.getId())).isEqualTo(1);
    }

    @Test void rejectsInvalidRange() throws Exception {
        Device d = device("127.0.0.1", List.of());
        mvc.perform(get("/api/v1/devices/" + d.getId() + "/metrics").header("Authorization", tokenFor(Role.ADMIN)).param("range", "99y"))
                .andExpect(status().isBadRequest());
    }
}
