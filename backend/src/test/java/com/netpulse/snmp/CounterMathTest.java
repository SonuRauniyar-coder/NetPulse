package com.netpulse.snmp;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CounterMathTest {
    private static BigInteger b(long v) { return BigInteger.valueOf(v); }
    private static final long GIG = 1_000_000_000L;

    @Test void plainDelta() {
        assertThat(CounterMath.rateBps(b(1_000), b(126_000), 64, 10, GIG)).hasValue(100_000.0);
    }

    @Test void thirtyTwoBitWrap() {
        BigInteger previous = b(4_294_967_000L);   // 296 below 2^32
        assertThat(CounterMath.rateBps(previous, b(704), 32, 8, GIG)).hasValue(1000.0);
    }

    @Test void sixtyFourBitDecreaseIsAResetNotAWrap() {
        assertThat(CounterMath.rateBps(b(5_000), b(100), 64, 10, GIG)).isEmpty();
    }

    @Test void thirtyTwoBitDecreaseImplyingMoreThanLineRateIsAReset() {
        // On a 100 Mbit/s link, a "wrap" in 10 seconds would mean about 3.4 Gbit/s: impossible, so a reset.
        assertThat(CounterMath.rateBps(b(100_000), b(50), 32, 10, 100_000_000L)).isEmpty();
    }

    @Test void valuesAboveLongMaxStayCorrect() {
        BigInteger huge = BigInteger.ONE.shiftLeft(63).add(b(1_000));
        assertThat(CounterMath.rateBps(huge, huge.add(b(1_000)), 64, 1, GIG)).hasValue(8_000.0);
    }

    @Test void noBaselineOrNoElapsedTimeGivesNoRate() {
        assertThat(CounterMath.rateBps(null, b(1), 64, 10, GIG)).isEmpty();
        assertThat(CounterMath.rateBps(b(1), null, 64, 10, GIG)).isEmpty();
        assertThat(CounterMath.rateBps(b(1), b(2), 64, 0, GIG)).isEmpty();
    }

    @Test void utilizationUsesTheBusierDirection() {
        assertThat(CounterMath.utilizationPct(10_000_000.0, 50_000_000.0, 100_000_000L)).isCloseTo(50.0, within(1e-9));
        assertThat(CounterMath.utilizationPct(10_000_000.0, null, 100_000_000L)).isCloseTo(10.0, within(1e-9));
    }

    @Test void unknownSpeedGivesNoUtilization() {
        assertThat(CounterMath.utilizationPct(1.0, 1.0, 0)).isNull();
        assertThat(CounterMath.utilizationPct(null, null, GIG)).isNull();
    }
}
