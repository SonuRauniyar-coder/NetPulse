package com.netpulse.common.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetValidatorTest {
    private final TargetValidator v = new TargetValidator();

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "192.168.1.1", "10.0.0.1", "127.0.0.1", "example.com", "a.b-c.example.org", "router1", "2001:4860:4860::8888", "::1", "fe80::1"})
    void acceptsValidTargets(String host) {
        assertThat(v.validateHost(host)).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "224.0.0.1", "239.1.1.1", "255.255.255.255", "ff02::1", "0.1.2.3", "240.0.0.1"})
    void rejectsSpecialPurposeAddresses(String host) {
        assertThatThrownBy(() -> v.validateHost(host)).isInstanceOf(InvalidTargetException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"999.1.1.1", "1.2.3", "010.1.1.1", "1.2.3.4.5", "-bad.example.com", "bad-.example.com", "has space.com", "a..b", "foo_bar.com", "1:2:3", "12345::1", "1::2::3", ":1:2:3:4:5:6:7"})
    void rejectsMalformedTargets(String host) {
        assertThatThrownBy(() -> v.validateHost(host)).isInstanceOf(InvalidTargetException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8; rm -rf /", "$(reboot)", "`id`", "a|b", "-c 1000", "--help", "a&b"})
    void rejectsCommandInjectionAttempts(String host) {
        assertThatThrownBy(() -> v.validateHost(host)).isInstanceOf(InvalidTargetException.class);
    }

    @Test void stripsIpv6BracketsAndLowercasesHostnames() {
        assertThat(v.validateHost("[::1]")).isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(v.validateHost("Example.COM")).isEqualTo("example.com");
    }

    @Test void resolveValidatesLiteralAddress() throws Exception {
        assertThat(v.resolve("127.0.0.1")).isEqualTo(InetAddress.getByName("127.0.0.1"));
    }

    @Test void rebindingToForbiddenAddressIsRejected() throws Exception {
        InetAddress multicast = InetAddress.getByAddress("evil.example", new byte[]{(byte) 224, 0, 0, 1});
        assertThatThrownBy(() -> v.rejectSpecial(multicast)).isInstanceOf(InvalidTargetException.class);
    }

    @Test void ipv6LiteralEdgeCases() {
        assertThat(IpLiterals.isIpv6("1:2:3:4:5:6:7:8")).isTrue();
        assertThat(IpLiterals.isIpv6("::ffff:192.0.2.1")).isTrue();
        assertThat(IpLiterals.isIpv6("1:2:3:4:5:6:7:8:9")).isFalse();
        assertThat(IpLiterals.isIpv6("1:2:3:4:5:6:7::8")).isFalse();
        assertThat(IpLiterals.isIpv6("gggg::1")).isFalse();
    }
}
