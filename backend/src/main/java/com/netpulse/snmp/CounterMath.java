package com.netpulse.snmp;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Turns SNMP octet counters into bits per second.
 *
 * <p>Counters only ever increase, so a rate needs two samples: bytes moved = current - previous. Three things can
 * go wrong:
 * <ul>
 *   <li><b>32-bit wrap.</b> ifInOctets rolls over at 2^32 (about 4.29 GB), in 34 seconds at 1 Gbit/s. If current is
 *       below previous on a 32-bit counter, the delta is 2^32 - previous + current. Two wraps inside one polling
 *       interval can't be detected, which is why 64-bit ifHCInOctets is preferred whenever the device has it.</li>
 *   <li><b>Device reboot.</b> Counters restart from 0, and a small "current" would look like a wrap and produce a
 *       huge fake spike. A sysUpTime lower than last time reveals the reboot, so that interval is discarded.</li>
 *   <li><b>Counter reset without a reboot</b> (for example "clear counters", or a line card swap). On 64-bit
 *       counters a real wrap would take centuries, so a decrease is always a reset. On 32-bit, a wrap that
 *       implies more than the interface's speed cannot be real, so it is treated as a reset too.</li>
 * </ul>
 * In each case the sample is dropped rather than guessed: an honest gap beats a false spike.
 */
public final class CounterMath {
    private static final BigInteger TWO_32 = BigInteger.ONE.shiftLeft(32);
    /** Allows for framing differences and timing jitter between the counter read and sysUpTime. */
    private static final double PLAUSIBLE_OVER_SPEED = 1.1;

    private CounterMath() { }

    /**
     * @param elapsedSeconds time between samples, from sysUpTime so it matches the agent's own clock
     * @param bits           32 or 64
     * @param speedBps       interface speed, or 0 if unknown
     * @return bits per second, or empty when no trustworthy rate exists for this interval
     */
    public static Optional<Double> rateBps(BigInteger previous, BigInteger current, int bits, double elapsedSeconds, long speedBps) {
        if (previous == null || current == null || elapsedSeconds <= 0) return Optional.empty();
        boolean wrapped = current.compareTo(previous) < 0;
        if (wrapped && bits != 32) return Optional.empty();
        BigInteger delta = wrapped ? TWO_32.subtract(previous).add(current) : current.subtract(previous);
        double bps = delta.doubleValue() * 8 / elapsedSeconds;
        if (wrapped && speedBps > 0 && bps > speedBps * PLAUSIBLE_OVER_SPEED) return Optional.empty();
        return Optional.of(bps);
    }

    /** Full duplex: each direction has the whole line rate, so utilization is that of the busier direction. */
    public static Double utilizationPct(Double inBps, Double outBps, long speedBps) {
        if (speedBps <= 0 || (inBps == null && outBps == null)) return null;
        double busiest = Math.max(inBps == null ? 0 : inBps, outBps == null ? 0 : outBps);
        return busiest * 100.0 / speedBps;
    }
}
