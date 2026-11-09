package com.netpulse.monitoring.ping;

import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.process.OsFamily;
import com.netpulse.common.process.ProcessFailureException;
import com.netpulse.common.process.ProcessResult;
import com.netpulse.common.process.ProcessRunner;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
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
        return ping(target, count, replyTimeout);
    }

    /** One quick probe, as used by the discovery sweep. */
    public PingOutcome pingOnce(InetAddress target, Duration timeout) {
        return ping(target, 1, timeout);
    }

    private PingOutcome ping(InetAddress target, int probes, Duration timeout) {
        try {
            ProcessResult r = runner.run(PingCommandBuilder.build(os, target, probes, timeout),
                    PingCommandBuilder.processTimeout(probes, timeout));
            return parser.parse(r.output(), probes);
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
