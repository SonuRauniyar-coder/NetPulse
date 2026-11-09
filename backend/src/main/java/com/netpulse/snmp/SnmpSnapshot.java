package com.netpulse.snmp;

import java.math.BigInteger;
import java.util.List;

/**
 * One poll's raw readings.
 *
 * @param uptimeTicks sysUpTime in hundredths of a second
 */
public record SnmpSnapshot(String sysName, String sysDescr, String sysLocation, long uptimeTicks, List<Interface> interfaces) {
    /** @param counterBits 64 if the octet counters came from ifHCIn/OutOctets, otherwise 32 */
    public record Interface(int index, String descr, Integer adminStatus, Integer operStatus, long speedBps,
                            BigInteger inOctets, BigInteger outOctets, int counterBits, BigInteger inErrors, BigInteger outErrors) { }
}
