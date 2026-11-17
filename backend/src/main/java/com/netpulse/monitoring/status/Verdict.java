package com.netpulse.monitoring.status;

/** Raw result of ONE cycle, before flap prevention. UNKNOWN means the monitor could not observe the target. */
public enum Verdict { UP, DEGRADED, FAIL, UNKNOWN }
