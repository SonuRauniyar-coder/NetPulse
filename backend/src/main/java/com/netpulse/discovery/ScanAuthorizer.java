package com.netpulse.discovery;

import com.netpulse.common.error.ApiException;
import com.netpulse.subnet.SubnetCalculator;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * The guard in front of every sweep. Probing address ranges you don't own can be unlawful, so a range must sit
 * entirely inside one authorized CIDR. By default that is loopback and RFC 1918 space only.
 *
 * <p>Single-device monitoring is deliberately not subject to this: it contacts one host a user chose to add.
 */
public final class ScanAuthorizer {
    private ScanAuthorizer() { }

    public static void check(SubnetCalculator.Cidr scan, List<String> authorized, int minPrefix) {
        if (scan.prefix() < minPrefix) {
            throw ApiException.badRequest("Scans are limited to /" + minPrefix + " (" + (1L << (32 - minPrefix)) + " addresses). Use a smaller range.");
        }
        boolean allowed = authorized.stream().map(SubnetCalculator::parse).anyMatch(a -> contains(a, scan));
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Range not authorized",
                    scan + " is outside the authorized scan ranges. An admin can change these in Settings.");
        }
    }

    static boolean contains(SubnetCalculator.Cidr outer, SubnetCalculator.Cidr inner) {
        return outer.prefix() <= inner.prefix() && (inner.network() & outer.mask()) == outer.network();
    }
}
