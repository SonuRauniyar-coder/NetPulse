package com.netpulse.monitoring.ping;

import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.process.OsFamily;
import com.netpulse.common.process.ProcessFailureException;
import com.netpulse.common.process.ProcessResult;
import com.netpulse.common.process.ProcessRunner;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;

@Service
public class PingService {
    private final ProcessRunner runner;
    private final OsFamily os;
    private final PingParser parser;
    private final int count;
    private final Duration replyTimeout;

    public PingService(ProcessRunner runner, NetPulseProperties props) {
        this(runner, OsFamily.detect(), props.monitoring().pingCount(), props.monitoring().pingReplyTimeout());
    }

    PingService(ProcessRunner runner, OsFamily os, int count, Duration replyTimeout) {
        this.runner = runner;
        this.os = os;
        this.parser = switch (os) {
            case LINUX -> new LinuxPingParser();
            case MACOS -> new MacPingParser();
            case WINDOWS -> new WindowsPingParser();
        };
        this.count = count;
        this.replyTimeout = replyTimeout;
    }

    public int probeCount() { return count; }

    public PingOutcome ping(InetAddress target) {
        try {
            ProcessResult r = runner.run(PingCommandBuilder.build(os, target, count, replyTimeout),
                    PingCommandBuilder.processTimeout(count, replyTimeout));
            return parser.parse(r.output(), count);
        } catch (ProcessFailureException e) {
            PingErrorCategory c = switch (e.getReason()) {
                case TOOL_MISSING -> PingErrorCategory.TOOL_MISSING;
                case PERMISSION_DENIED -> PingErrorCategory.PERMISSION_DENIED;
                case IO_ERROR -> PingErrorCategory.UNKNOWN;
            };
            return PingOutcome.failure(c, e.getMessage(), 0);
        }
    }
}
