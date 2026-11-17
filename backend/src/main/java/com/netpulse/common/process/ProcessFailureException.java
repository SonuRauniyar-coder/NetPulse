package com.netpulse.common.process;

/** The tool could not be started at all (missing binary, no permission). Distinct from "ran but failed". */
public class ProcessFailureException extends RuntimeException {
    public enum Reason { TOOL_MISSING, PERMISSION_DENIED, IO_ERROR }

    private final Reason reason;

    public ProcessFailureException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
