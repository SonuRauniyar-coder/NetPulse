package com.netpulse.monitoring.dns;

import com.netpulse.common.config.NetPulseProperties;
import com.netpulse.common.net.TargetValidator;
import com.netpulse.device.DnsRecordType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.xbill.DNS.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * DNS lookups through dnsjava, because {@code InetAddress} can only return A/AAAA, cannot target a specific
 * server, and hides the response code. We send a raw query so we can see the actual RCODE.
 * The cache-less low-level API is used on purpose: a monitor must measure the server, not a local cache.
 */
@Component
public class DnsChecker {
    private final Duration timeout;
    private final TargetValidator validator;

    @Autowired
    public DnsChecker(NetPulseProperties props, TargetValidator validator) {
        this(props.monitoring().dnsTimeout(), validator);
    }

    DnsChecker(Duration timeout, TargetValidator validator) {
        this.timeout = timeout;
        this.validator = validator;
    }

    /** @param deviceAddress used for PTR checks without an explicit name */
    public DnsOutcome check(DnsRecordType type, String queryName, String server, InetAddress deviceAddress) {
        String shownName = queryName;
        long start = System.nanoTime();
        try {
            Name name;
            if (type == DnsRecordType.PTR) {
                InetAddress ip = queryName != null ? validator.resolve(queryName) : deviceAddress;
                if (ip == null) return failed(type, "(device ip)", server, DnsResultKind.ERROR, "Device address is unknown");
                shownName = ip.getHostAddress();
                name = ReverseMap.fromAddress(ip);   // 8.8.8.8 -> 8.8.8.8.in-addr.arpa.
            } else {
                name = Name.fromString(queryName, Name.root);
            }
            Message query = Message.newQuery(org.xbill.DNS.Record.newRecord(name, Type.value(type.name()), DClass.IN));
            Resolver resolver = resolverFor(server);
            resolver.setTimeout(timeout);
            start = System.nanoTime();
            Message response = resolver.send(query);
            double ms = (System.nanoTime() - start) / 1e6;
            List<String> answers = response.getSection(Section.ANSWER).stream()
                    .filter(r -> r.getType() == Type.value(type.name()))
                    .map(r -> r.rdataToString()).toList();
            DnsResultKind kind = classifyRcode(response.getRcode(), answers.size());
            return new DnsOutcome(type, shownName, server, kind, ms, answers,
                    kind == DnsResultKind.NOERROR ? null : Rcode.string(response.getRcode()));
        } catch (TextParseException e) {
            return failed(type, shownName, server, DnsResultKind.ERROR, "Invalid name");
        } catch (IOException e) {
            return failed(type, shownName, server, classifyException(e), e.getMessage());
        } catch (RuntimeException e) {
            return failed(type, shownName, server, DnsResultKind.ERROR, e.getMessage());
        }
    }

    protected Resolver resolverFor(String server) throws IOException {
        if (server == null) {
            ExtendedResolver r = new ExtendedResolver();   // servers from the OS configuration
            r.setRetries(1);
            return r;
        }
        return new SimpleResolver(validator.resolve(server));
    }

    static DnsResultKind classifyRcode(int rcode, int answers) {
        return switch (rcode) {
            case Rcode.NOERROR -> answers > 0 ? DnsResultKind.NOERROR : DnsResultKind.NODATA;
            case Rcode.NXDOMAIN -> DnsResultKind.NXDOMAIN;
            case Rcode.SERVFAIL -> DnsResultKind.SERVFAIL;
            case Rcode.REFUSED -> DnsResultKind.REFUSED;
            default -> DnsResultKind.ERROR;
        };
    }

    static DnsResultKind classifyException(IOException e) {
        boolean timeout = e instanceof SocketTimeoutException || e.getCause() instanceof TimeoutException
                || String.valueOf(e.getMessage()).toLowerCase().contains("timed out");
        return timeout ? DnsResultKind.TIMEOUT : DnsResultKind.ERROR;
    }

    private static DnsOutcome failed(DnsRecordType t, String name, String server, DnsResultKind kind, String error) {
        String msg = error == null ? kind.name() : error.substring(0, Math.min(200, error.length()));
        return new DnsOutcome(t, name == null ? "" : name, server, kind, null, List.of(), msg);
    }
}
