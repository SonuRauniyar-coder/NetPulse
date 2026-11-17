package com.netpulse.discovery;

import com.netpulse.common.error.ApiException;
import com.netpulse.common.process.OsFamily;
import com.netpulse.subnet.SubnetCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class DiscoveryUnitsTest {
    private static final List<String> DEFAULT_CIDRS = List.of("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    @Test void linuxNeighbors() {
        var m = NeighborTableParser.parse(OsFamily.LINUX, """
                192.168.1.1 dev eth0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
                192.168.1.2 dev eth0 lladdr 00:22:33:44:55:66 STALE
                192.168.1.9 dev eth0  FAILED
                192.168.1.10 dev eth0  INCOMPLETE
                fe80::1 dev eth0 lladdr aa:bb:cc:dd:ee:00 router REACHABLE
                """);
        assertThat(m).containsOnlyKeys("192.168.1.1", "192.168.1.2");
        assertThat(m.get("192.168.1.1")).isEqualTo("AA:BB:CC:DD:EE:FF");
    }

    @Test void macOsNeighborsAsCapturedArePadded() {
        var m = NeighborTableParser.parse(OsFamily.MACOS, """
                ? (10.179.41.84) at 96:cc:76:78:b9:f on en0 ifscope [ethernet]
                ? (10.179.41.106) at de:b6:55:a0:ff:e2 on en0 ifscope permanent [ethernet]
                ? (10.179.41.255) at ff:ff:ff:ff:ff:ff on en0 ifscope [ethernet]
                ? (224.0.0.251) at 1:0:5e:0:0:fb on en0 ifscope permanent [ethernet]
                ? (10.179.41.7) at (incomplete) on en0 ifscope [ethernet]
                """);
        assertThat(m).containsOnlyKeys("10.179.41.84", "10.179.41.106");
        assertThat(m.get("10.179.41.84")).isEqualTo("96:CC:76:78:B9:0F");
    }

    @Test void windowsNeighborsIgnoreTheLocalizedTypeColumn() {
        var m = NeighborTableParser.parse(OsFamily.WINDOWS, """

                Interface: 192.168.1.50 --- 0x5
                  Internet Address      Physical Address      Type
                  192.168.1.1           aa-bb-cc-dd-ee-ff     dynamic
                  192.168.1.2           00-22-33-44-55-66     dynamisch
                  192.168.1.255         ff-ff-ff-ff-ff-ff     static
                  224.0.0.22            01-00-5e-00-00-16     static
                """);
        assertThat(m).containsOnlyKeys("192.168.1.1", "192.168.1.2");
    }

    @Test void ouiFindsRealVendorsAndLabelsRandomizedMacs() {
        OuiDatabase oui = new OuiDatabase();
        assertThat(oui.lookup("00:00:0C:12:34:56")).containsIgnoringCase("Cisco");
        assertThat(oui.lookup("02:00:00:00:00:01")).isEqualTo(OuiDatabase.RANDOMIZED);
        assertThat(oui.lookup(null)).isNull();
    }

    @Test void authorizedRangesPass() {
        assertThatCode(() -> ScanAuthorizer.check(SubnetCalculator.parse("192.168.1.0/24"), DEFAULT_CIDRS, 22)).doesNotThrowAnyException();
        assertThatCode(() -> ScanAuthorizer.check(SubnetCalculator.parse("10.20.0.0/22"), DEFAULT_CIDRS, 22)).doesNotThrowAnyException();
    }

    @Test void publicRangesAreForbiddenByDefault() {
        var e = catchThrowableOfType(ApiException.class, () -> ScanAuthorizer.check(SubnetCalculator.parse("8.8.8.0/24"), DEFAULT_CIDRS, 22));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void aRangeMustSitEntirelyInsideAnAuthorizedOne() {
        // 172.16.0.0/12 ends at 172.31.255.255, so this one crosses out of private space.
        assertThat(catchThrowableOfType(ApiException.class,
                () -> ScanAuthorizer.check(SubnetCalculator.parse("172.32.0.0/22"), DEFAULT_CIDRS, 22)).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void oversizedScansAreRejectedEvenInsideAuthorizedSpace() {
        assertThat(catchThrowableOfType(ApiException.class,
                () -> ScanAuthorizer.check(SubnetCalculator.parse("10.0.0.0/21"), DEFAULT_CIDRS, 22)).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
