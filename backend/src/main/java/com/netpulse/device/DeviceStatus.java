package com.netpulse.device;

/**
 * UP: reachable and healthy. DEGRADED: reachable but over a threshold or a configured check failed.
 * DOWN: ALL configured checks failed, N cycles in a row. UNKNOWN: not observed yet, or the monitor itself
 * cannot observe (e.g. ping tool missing). PAUSED: monitoring disabled by a user.
 */
public enum DeviceStatus { UP, DEGRADED, DOWN, UNKNOWN, PAUSED }
