package com.netpulse.subnet;

import com.netpulse.common.error.ApiException;
import com.netpulse.subnet.SubnetCalculator.Requirement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubnetCalculatorTest {
    @Test void ccnaSlash26FromAHostAddress() {
        var s = SubnetCalculator.calculate("192.168.10.77/26");
        assertThat(s.cidr()).isEqualTo("192.168.10.64/26");
        assertThat(s.network()).isEqualTo("192.168.10.64");
        assertThat(s.broadcast()).isEqualTo("192.168.10.127");
        assertThat(s.firstHost()).isEqualTo("192.168.10.65");
        assertThat(s.lastHost()).isEqualTo("192.168.10.126");
        assertThat(s.usableHosts()).isEqualTo(62);
        assertThat(s.totalAddresses()).isEqualTo(64);
        assertThat(s.subnetMask()).isEqualTo("255.255.255.192");
        assertThat(s.wildcardMask()).isEqualTo("0.0.0.63");
        assertThat(s.addressScope()).isEqualTo("Private (RFC 1918)");
        assertThat(s.historicalClass()).isEqualTo("C");
        assertThat(s.note()).contains("host inside");
    }

    @ParameterizedTest
    @CsvSource({
            "10.0.0.1/8,10.0.0.0,10.255.255.255,16777214",
            "172.16.35.123/20,172.16.32.0,172.16.47.255,4094",
            "192.168.1.130/25,192.168.1.128,192.168.1.255,126",
            "192.168.1.1/30,192.168.1.0,192.168.1.3,2",
            "131.107.2.200/19,131.107.0.0,131.107.31.255,8190",
    })
    void ccnaExamples(String cidr, String network, String broadcast, long usable) {
        var s = SubnetCalculator.calculate(cidr);
        assertThat(s.network()).isEqualTo(network);
        assertThat(s.broadcast()).isEqualTo(broadcast);
        assertThat(s.usableHosts()).isEqualTo(usable);
    }

    @Test void slash31HasTwoUsableAddressesAndNoBroadcast() {
        var s = SubnetCalculator.calculate("10.1.1.1/31");
        assertThat(s.network()).isEqualTo("10.1.1.0");
        assertThat(s.broadcast()).isNull();
        assertThat(s.binary().broadcast()).isNull();
        assertThat(s.firstHost()).isEqualTo("10.1.1.0");
        assertThat(s.lastHost()).isEqualTo("10.1.1.1");
        assertThat(s.usableHosts()).isEqualTo(2);
        assertThat(s.note()).contains("RFC 3021");
    }

    @Test void slash32IsOneHost() {
        var s = SubnetCalculator.calculate("8.8.8.8/32");
        assertThat(s.broadcast()).isNull();
        assertThat(s.firstHost()).isEqualTo("8.8.8.8").isEqualTo(s.lastHost());
        assertThat(s.usableHosts()).isEqualTo(1);
        assertThat(s.subnetMask()).isEqualTo("255.255.255.255");
        assertThat(s.wildcardMask()).isEqualTo("0.0.0.0");
        assertThat(s.addressScope()).isEqualTo("Public");
    }

    @Test void slash0CoversEverything() {
        var s = SubnetCalculator.calculate("1.2.3.4/0");
        assertThat(s.network()).isEqualTo("0.0.0.0");
        assertThat(s.broadcast()).isEqualTo("255.255.255.255");
        assertThat(s.subnetMask()).isEqualTo("0.0.0.0");
        assertThat(s.totalAddresses()).isEqualTo(1L << 32);
    }

    @Test void binaryView() {
        var b = SubnetCalculator.calculate("192.168.10.77/26").binary();
        assertThat(b.address()).isEqualTo("11000000.10101000.00001010.01001101");
        assertThat(b.mask()).isEqualTo("11111111.11111111.11111111.11000000");
        assertThat(b.network()).isEqualTo("11000000.10101000.00001010.01000000");
    }

    @ParameterizedTest
    @CsvSource({
            "10.5.5.5,Private (RFC 1918)", "172.31.255.1,Private (RFC 1918)", "172.32.0.1,Public", "192.168.0.1,Private (RFC 1918)",
            "127.0.0.1,Special: loopback", "169.254.1.1,Special: link-local", "100.64.0.1,Special: shared CGNAT space (RFC 6598)",
            "100.128.0.1,Public", "192.0.2.5,Special: documentation (TEST-NET-1)", "224.0.0.5,Special: multicast", "240.0.0.1,Special: reserved", "1.1.1.1,Public",
    })
    void scopes(String ip, String scope) {
        assertThat(SubnetCalculator.scope(SubnetCalculator.toLong(ip))).isEqualTo(scope);
    }

    @ParameterizedTest
    @CsvSource({"1.0.0.0/32,A", "127.0.0.1/32,A", "128.0.0.1/32,B", "191.1.1.1/32,B", "192.0.0.1/32,C", "223.1.1.1/32,C", "224.0.0.1/32,D", "240.0.0.1/32,E"})
    void historicalClasses(String cidr, String cls) {
        assertThat(SubnetCalculator.calculate(cidr).historicalClass()).isEqualTo(cls);
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.0", "192.168.1.0/33", "192.168.1.0/-1", "192.168.1/24", "300.1.1.1/24", "abc/24", "1.1.1.1/2 4", "", "::1/64"})
    void rejectsBadInput(String input) {
        assertThatThrownBy(() -> SubnetCalculator.parse(input)).isInstanceOf(ApiException.class);
    }

    @Test void vlsmClassicExampleLargestFirstAndContiguous() {
        var r = SubnetCalculator.vlsm("192.168.1.0/24", List.of(
                new Requirement("WAN", 2), new Requirement("Sales", 50), new Requirement("IT", 20), new Requirement("HR", 10)));
        assertThat(r.allocations()).extracting(SubnetCalculator.Allocation::name).containsExactly("Sales", "IT", "HR", "WAN");
        assertThat(r.allocations()).extracting(SubnetCalculator.Allocation::cidr)
                .containsExactly("192.168.1.0/26", "192.168.1.64/27", "192.168.1.96/28", "192.168.1.112/30");
        assertThat(r.allocations().getFirst().usableHosts()).isEqualTo(62);
        assertThat(r.allocations().getFirst().wastedHosts()).isEqualTo(12);
        assertThat(r.allocations().getLast().broadcast()).isEqualTo("192.168.1.115");
        assertThat(r.allocatedAddresses()).isEqualTo(116);
        assertThat(r.freeAddresses()).isEqualTo(140);
    }

    @Test void vlsmSizesForTheBoundary() {
        // 62 fits a /26 exactly; 63 does not, because the network and broadcast addresses are reserved.
        assertThat(SubnetCalculator.vlsm("10.0.0.0/24", List.of(new Requirement("a", 62))).allocations().getFirst().cidr()).isEqualTo("10.0.0.0/26");
        assertThat(SubnetCalculator.vlsm("10.0.0.0/24", List.of(new Requirement("a", 63))).allocations().getFirst().cidr()).isEqualTo("10.0.0.0/25");
        assertThat(SubnetCalculator.vlsm("10.0.0.0/24", List.of(new Requirement("a", 1))).allocations().getFirst().cidr()).isEqualTo("10.0.0.0/30");
    }

    @Test void vlsmReportsWhatDoesNotFit() {
        assertThatThrownBy(() -> SubnetCalculator.vlsm("192.168.1.0/28", List.of(new Requirement("Big", 100))))
                .isInstanceOf(ApiException.class).hasMessageContaining("Big");
        assertThatThrownBy(() -> SubnetCalculator.vlsm("192.168.1.0/26", List.of(new Requirement("A", 30), new Requirement("B", 30), new Requirement("C", 30))))
                .isInstanceOf(ApiException.class).hasMessageContaining("'C'");
    }

    @Test void vlsmUsesTheNetworkAddressEvenWhenGivenAHost() {
        assertThat(SubnetCalculator.vlsm("10.1.2.3/24", List.of(new Requirement("a", 10))).allocations().getFirst().network()).isEqualTo("10.1.2.0");
    }

    @Test void hostAddressesSkipNetworkAndBroadcast() {
        assertThat(SubnetCalculator.hostAddresses(SubnetCalculator.parse("10.0.0.0/30")).stream().map(SubnetCalculator::format))
                .containsExactly("10.0.0.1", "10.0.0.2");
        assertThat(SubnetCalculator.hostAddresses(SubnetCalculator.parse("10.0.0.0/31"))).hasSize(2);
        assertThat(SubnetCalculator.hostAddresses(SubnetCalculator.parse("10.0.0.9/32"))).hasSize(1);
    }
}
