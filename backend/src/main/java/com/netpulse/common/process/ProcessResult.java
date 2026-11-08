package com.netpulse.common.process;

/**
 * Outcome of running an external command.
 *
 * @param exitCode exit status, or -1 if the process was killed because it exceeded its timeout
 * @param output   combined stdout+stderr (tools like ping print errors to stderr)
 * @param timedOut true if we had to kill the process
 */
public record ProcessResult(int exitCode, String output, boolean timedOut, long durationMs) {
}
