package com.netpulse.monitoring.ping;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PingStatsTest {
    @Test void lossFromProbeCounts() {
        assertThat(PingStats.lossPct(4, 4)).isZero();
        assertThat(PingStats.lossPct(4, 3)).isEqualTo(25.0);
        assertThat(PingStats.lossPct(4, 0)).isEqualTo(100.0);
    }

    @Test void lossHandlesDegenerateCounts() {
        assertThat(PingStats.lossPct(0, 0)).isEqualTo(100.0);
        assertThat(PingStats.lossPct(4, 6)).isZero();
    }

    @Test void jitterIsMeanAbsoluteConsecutiveDifference() {
        // |20-10| + |15-20| + |25-15| = 25, over 3 gaps
        assertThat(PingStats.jitter(List.of(10.0, 20.0, 15.0, 25.0))).isCloseTo(25.0 / 3, within(1e-9));
    }

    @Test void jitterOfSteadyLatencyIsZero() {
        assertThat(PingStats.jitter(List.of(5.0, 5.0, 5.0))).isZero();
    }

    @Test void jitterNeedsTwoSamples() {
        assertThat(PingStats.jitter(List.of(5.0))).isNull();
        assertThat(PingStats.jitter(List.of())).isNull();
    }

    @Test void aggregatesOfEmptyAreNull() {
        assertThat(PingStats.min(List.of())).isNull();
        assertThat(PingStats.avg(List.of())).isNull();
        assertThat(PingStats.max(List.of())).isNull();
    }
}
