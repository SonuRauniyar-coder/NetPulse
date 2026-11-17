package com.netpulse.monitoring.dns;

import com.netpulse.common.net.TargetValidator;
import com.netpulse.device.DnsRecordType;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DnsCheckerTest {
    private final Resolver resolver = mock(Resolver.class);
    private final DnsChecker checker = new DnsChecker(Duration.ofSeconds(1), new TargetValidator()) {
        @Override protected Resolver resolverFor(String server) { return resolver; }
    };

    private Message response(String name, int rcode, org.xbill.DNS.Record... answers) throws Exception {
        Message m = new Message();
        m.getHeader().setRcode(rcode);
        m.addRecord(org.xbill.DNS.Record.newRecord(Name.fromString(name, Name.root), Type.A, DClass.IN), Section.QUESTION);
        for (org.xbill.DNS.Record r : answers) m.addRecord(r, Section.ANSWER);
        return m;
    }

    @Test void noerrorWithAnswer() throws Exception {
        var a = new ARecord(Name.fromString("example.com."), DClass.IN, 60, InetAddress.getByName("93.184.216.34"));
        when(resolver.send(any())).thenReturn(response("example.com", Rcode.NOERROR, a));
        DnsOutcome o = checker.check(DnsRecordType.A, "example.com", null, null);
        assertThat(o.kind()).isEqualTo(DnsResultKind.NOERROR);
        assertThat(o.answers()).containsExactly("93.184.216.34");
        assertThat(o.resolveMs()).isNotNull();
    }

    @Test void nxdomain() throws Exception {
        when(resolver.send(any())).thenReturn(response("nope.example.com", Rcode.NXDOMAIN));
        assertThat(checker.check(DnsRecordType.A, "nope.example.com", null, null).kind()).isEqualTo(DnsResultKind.NXDOMAIN);
    }

    @Test void servfail() throws Exception {
        when(resolver.send(any())).thenReturn(response("example.com", Rcode.SERVFAIL));
        assertThat(checker.check(DnsRecordType.A, "example.com", null, null).kind()).isEqualTo(DnsResultKind.SERVFAIL);
    }

    @Test void nodataIsNoerrorWithoutAnswers() throws Exception {
        when(resolver.send(any())).thenReturn(response("example.com", Rcode.NOERROR));
        assertThat(checker.check(DnsRecordType.AAAA, "example.com", null, null).kind()).isEqualTo(DnsResultKind.NODATA);
    }

    @Test void timeout() throws Exception {
        when(resolver.send(any())).thenThrow(new SocketTimeoutException("timed out"));
        DnsOutcome o = checker.check(DnsRecordType.A, "example.com", null, null);
        assertThat(o.kind()).isEqualTo(DnsResultKind.TIMEOUT);
        assertThat(o.kind().serverResponded()).isFalse();
    }

    @Test void otherIoErrorIsNotCalledTimeout() throws Exception {
        when(resolver.send(any())).thenThrow(new IOException("network unreachable"));
        assertThat(checker.check(DnsRecordType.A, "example.com", null, null).kind()).isEqualTo(DnsResultKind.ERROR);
    }

    @Test void ptrDefaultsToDeviceAddress() throws Exception {
        when(resolver.send(any())).thenReturn(response("8.8.8.8.in-addr.arpa", Rcode.NXDOMAIN));
        DnsOutcome o = checker.check(DnsRecordType.PTR, null, null, InetAddress.getByName("8.8.8.8"));
        assertThat(o.queryName()).isEqualTo("8.8.8.8");
    }
}
