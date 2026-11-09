package com.netpulse.traceroute;

import com.netpulse.audit.AuditService;
import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.error.ApiException;
import com.netpulse.common.net.InvalidTargetException;
import com.netpulse.common.net.TargetValidator;
import com.netpulse.common.process.OsFamily;
import com.netpulse.common.process.ProcessFailureException;
import com.netpulse.common.process.ProcessResult;
import com.netpulse.common.process.ProcessRunner;
import com.netpulse.common.web.CurrentUser;
import com.netpulse.common.web.ToolRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * Runs the OS traceroute and streams each hop over STOMP as it appears.
 *
 * <p>How traceroute works: packets are sent with TTL 1, 2, 3 ... Each router decrements the TTL, and the one that
 * reaches 0 drops the packet and returns ICMP Time Exceeded, which reveals its address. The destination answers
 * differently (ICMP Port Unreachable for UDP probes, Echo Reply for ICMP probes), and that ends the trace.
 *
 * <p>Runs are short-lived and kept in memory for a few minutes so a client that subscribes late can catch up.
 */
@Service
public class TracerouteService {
    public static final String TOPIC_PREFIX = "/topic/traceroute/";
    private static final Logger log = LoggerFactory.getLogger(TracerouteService.class);
    private static final Duration KEEP_FINISHED = Duration.ofMinutes(10);

    public enum Status { RUNNING, COMPLETED, TIMED_OUT, FAILED }

    public record Run(UUID id, String target, String ip, Status status, boolean reachedTarget, String error,
                      List<TracerouteHop> hops, Instant startedAt, Instant finishedAt) { }

    private static final class State {
        final UUID id = UUID.randomUUID();
        final String target;
        final String ip;
        final Instant startedAt;
        final List<TracerouteHop> hops = new ArrayList<>();
        Status status = Status.RUNNING;
        String error;
        Instant finishedAt;

        State(String target, String ip, Instant startedAt) {
            this.target = target;
            this.ip = ip;
            this.startedAt = startedAt;
        }

        synchronized Run snapshot() {
            boolean reached = hops.stream().anyMatch(h -> ip.equals(h.ip()) || h.otherIps().contains(ip));
            return new Run(id, target, ip, status, reached, error, List.copyOf(hops), startedAt, finishedAt);
        }
    }

    private final Map<UUID, State> runs = new ConcurrentHashMap<>();
    private final ProcessRunner runner;
    private final TargetValidator validator;
    private final SimpMessagingTemplate messaging;
    private final ExecutorService executor;
    private final AuditService audit;
    private final ToolRateLimiter rateLimiter;
    private final NetPulseProperties props;
    private final Clock clock;
    private final OsFamily os = OsFamily.detect();
    private final TracerouteParser parser;
    private final Semaphore slots;

    public TracerouteService(ProcessRunner runner, TargetValidator validator, SimpMessagingTemplate messaging,
                             ExecutorService monitoringExecutor, AuditService audit, ToolRateLimiter rateLimiter,
                             NetPulseProperties props, Clock clock) {
        this.runner = runner;
        this.validator = validator;
        this.messaging = messaging;
        this.executor = monitoringExecutor;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
        this.parser = os == OsFamily.WINDOWS ? new WindowsTracerouteParser() : new UnixTracerouteParser();
        this.slots = new Semaphore(props.traceroute().maxConcurrent());
    }

    public Run start(String rawTarget) {
        if (props.demoMode()) throw ApiException.conflict("Traceroute doesn't run in demo mode.");
        rateLimiter.acquire("traceroute", CurrentUser.name(), props.traceroute().perUserPerMinute());
        String host = validator.validateHost(rawTarget);
        InetAddress address;
        try {
            address = validator.resolve(host);   // the probe goes to this validated address, never the name again
        } catch (UnknownHostException e) {
            throw new InvalidTargetException("Could not resolve " + host);
        }
        if (!slots.tryAcquire()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", "Too many traces are running. Try again in a moment.");
        }
        State state = new State(host, address.getHostAddress(), clock.instant());
        try {
            evictOld();
            runs.put(state.id, state);
            audit.record("TRACEROUTE_RUN", "traceroute", state.id, "Traced " + host + " (" + state.ip + ")");
            executor.submit(() -> execute(state, address));
        } catch (RuntimeException e) {
            slots.release();
            runs.remove(state.id);
            throw e;
        }
        return state.snapshot();
    }

    public Optional<Run> find(UUID id) {
        return Optional.ofNullable(runs.get(id)).map(State::snapshot);
    }

    private void execute(State state, InetAddress address) {
        var cfg = props.traceroute();
        try {
            int[] silent = {0};
            ProcessResult r = runner.runStreaming(
                    TracerouteCommandBuilder.build(os, address, cfg.maxHops(), cfg.probesPerHop(), cfg.probeTimeout()),
                    cfg.runTimeout(),
                    line -> {
                        Optional<TracerouteHop> hop = parser.parseLine(line);
                        if (hop.isEmpty()) return true;
                        accept(state, hop.get());
                        silent[0] = hop.get().ip() == null ? silent[0] + 1 : 0;
                        return silent[0] < cfg.maxSilentHops();   // a filtered path would otherwise wait out every remaining TTL
                    });
            finish(state, r.timedOut() ? Status.TIMED_OUT : Status.COMPLETED,
                    r.timedOut() ? "Stopped after " + cfg.runTimeout().toSeconds() + " seconds" : null);
        } catch (ProcessFailureException e) {
            finish(state, Status.FAILED, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Traceroute {} failed", state.id, e);
            finish(state, Status.FAILED, "Traceroute failed unexpectedly");
        } finally {
            slots.release();
        }
    }

    private void accept(State state, TracerouteHop parsed) {
        TracerouteHop published;
        synchronized (state) {
            if (parsed.hop() == 0 && !state.hops.isEmpty()) {
                published = merge(state.hops.getLast(), parsed);
                state.hops.set(state.hops.size() - 1, published);
            } else if (parsed.hop() == 0) {
                return;
            } else {
                published = parsed;
                state.hops.add(parsed);
            }
        }
        send(state.id, new HopEvent("hop", published));
    }

    /** Folds a macOS continuation line (extra ECMP responder) into its hop. */
    static TracerouteHop merge(TracerouteHop base, TracerouteHop extra) {
        List<Double> rtts = new ArrayList<>(base.rttsMs());
        rtts.addAll(extra.rttsMs());
        List<String> others = new ArrayList<>(base.otherIps());
        if (extra.ip() != null && !extra.ip().equals(base.ip()) && !others.contains(extra.ip())) others.add(extra.ip());
        return new TracerouteHop(base.hop(), base.ip(), base.hostname(), rtts, others, base.note() != null ? base.note() : extra.note());
    }

    private void finish(State state, Status status, String error) {
        synchronized (state) {
            state.status = status;
            state.error = error;
            state.finishedAt = clock.instant();
        }
        send(state.id, new DoneEvent("done", state.snapshot()));
    }

    public record HopEvent(String type, TracerouteHop hop) { }
    public record DoneEvent(String type, Run run) { }

    private void send(UUID id, Object event) {
        try {
            messaging.convertAndSend(TOPIC_PREFIX + id, event);
        } catch (RuntimeException e) {
            log.debug("Live traceroute update dropped: {}", e.getMessage());   // clients can still read the snapshot
        }
    }

    private void evictOld() {
        Instant cutoff = clock.instant().minus(KEEP_FINISHED);
        runs.values().removeIf(s -> {
            synchronized (s) {
                return s.finishedAt != null && s.finishedAt.isBefore(cutoff);
            }
        });
    }
}
