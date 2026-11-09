package com.netpulse.common.net;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validates monitoring targets (IP literal or hostname) and the addresses they resolve to.
 *
 * <p>Single-target monitoring MAY point at public hosts (websites, 8.8.8.8 ...) - that is a core feature.
 * What we reject are addresses that make no sense as a unicast monitoring target:
 * unspecified (0.0.0.0, ::), "this network" 0.0.0.0/8, multicast (224.0.0.0/4, ff00::/8),
 * limited broadcast 255.255.255.255 and the reserved 240.0.0.0/4 block.
 *
 * <p>Loopback and link-local (including the 169.254.169.254 cloud metadata address) point at the monitoring host
 * itself rather than the network, so they are refused unless {@code allow-local-targets} is on.
 *
 * <p>IPv6 forms that carry an IPv4 address (IPv4-compatible, NAT64 and 6to4) are judged by the address they carry.
 *
 * <p>DNS rebinding: a hostname can resolve to a harmless address when the user saves it and to a forbidden
 * one later. So the engine calls {@link #resolve} on every check, validates the RESULTING address, and then
 * connects to that exact InetAddress - never to the hostname again.
 */
@Component
public class TargetValidator {

    // RFC 1123 label: letters, digits, hyphen; 1-63 chars; must not start or end with '-'
    private static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");

    private final boolean allowLocalTargets;

    public TargetValidator(@Value("${netpulse.monitoring.allow-local-targets:false}") boolean allowLocalTargets) {
        this.allowLocalTargets = allowLocalTargets;
    }

    public boolean allowsLocalTargets() {
        return allowLocalTargets;
    }

    /** Syntax + special-address check of a user-entered host. Returns a normalized form. No DNS. */
    public String validateHost(String raw) {
        if (raw == null || raw.isBlank()) throw new InvalidTargetException("Host is required");
        String host = raw.trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);

        Optional<InetAddress> literal = IpLiterals.parse(host);
        if (literal.isPresent()) {
            rejectSpecial(literal.get());
            return literal.get().getHostAddress();   // canonical text form, e.g. 2001:db8:0:0:0:0:0:1
        }
        if (host.contains(":")) throw new InvalidTargetException("'" + raw + "' is not a valid IPv6 address");
        if (looksLikeIpv4Attempt(host)) throw new InvalidTargetException("'" + raw + "' is not a valid IPv4 address");
        if (!isValidHostname(host)) throw new InvalidTargetException("'" + raw + "' is not a valid hostname");
        String normalized = host.toLowerCase(Locale.ROOT);
        String bare = normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
        if (!allowLocalTargets && (bare.equals("localhost") || bare.endsWith(".localhost"))) {
            throw new InvalidTargetException(raw + " is the monitoring host itself");
        }
        return normalized;
    }

    public static boolean isValidHostname(String host) {
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (h.isEmpty() || h.length() > 253) return false;
        String[] labels = h.split("\\.", -1);
        if (Arrays.stream(labels).anyMatch(l -> !LABEL.matcher(l).matches())) return false;
        // top-level label must not be all-numeric (otherwise "1.2.3.999" would pass as a hostname)
        return !labels[labels.length - 1].chars().allMatch(Character::isDigit);
    }

    private static boolean looksLikeIpv4Attempt(String host) {
        return host.chars().allMatch(c -> Character.isDigit(c) || c == '.');
    }

    /**
     * Resolves a (validated) host and returns the first address that is allowed.
     * Every resolved address is checked - if a hostname resolves only to forbidden addresses we refuse.
     */
    public InetAddress resolve(String host) throws UnknownHostException {
        Optional<InetAddress> literal = IpLiterals.parse(host);
        InetAddress[] candidates = literal.isPresent() ? new InetAddress[]{literal.get()} : InetAddress.getAllByName(host);
        InvalidTargetException last = null;
        // Prefer IPv4 when both exist: ICMP/TCP paths for v4 are the most widely reachable.
        for (InetAddress a : sortV4First(candidates)) {
            try {
                rejectSpecial(a);
                return a;
            } catch (InvalidTargetException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new UnknownHostException(host);
    }

    private static Optional<InetAddress> embeddedIpv4(byte[] v6) {
        boolean ipv4Compatible = Arrays.equals(v6, 0, 12, new byte[12], 0, 12);
        boolean nat64 = v6[0] == 0 && v6[1] == 0x64 && (v6[2] & 0xff) == 0xff && (v6[3] & 0xff) == 0x9b && Arrays.equals(v6, 4, 12, new byte[8], 0, 8);
        boolean sixToFour = v6[0] == 0x20 && v6[1] == 0x02;
        if (!ipv4Compatible && !nat64 && !sixToFour) return Optional.empty();
        int start = sixToFour ? 2 : 12;
        try {
            return Optional.of(InetAddress.getByAddress(Arrays.copyOfRange(v6, start, start + 4)));
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);   // four bytes is always a valid length
        }
    }

    private static InetAddress[] sortV4First(InetAddress[] in) {
        return Arrays.stream(in).sorted((a, b) -> Boolean.compare(a instanceof Inet6Address, b instanceof Inet6Address))
                .toArray(InetAddress[]::new);
    }

    public void rejectSpecial(InetAddress a) {
        String text = a.getHostAddress();
        if (a.isAnyLocalAddress()) throw new InvalidTargetException(text + " is the unspecified address");
        if (a.isMulticastAddress()) throw new InvalidTargetException(text + " is a multicast address");
        if (!allowLocalTargets && a.isLoopbackAddress()) throw new InvalidTargetException(text + " is a loopback address");
        if (!allowLocalTargets && a.isLinkLocalAddress()) throw new InvalidTargetException(text + " is a link-local address");
        if (a instanceof Inet6Address && !a.isLoopbackAddress()) embeddedIpv4(a.getAddress()).ifPresent(this::rejectSpecial);
        if (a instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(a.getAddress()[0]);
            if (first == 0) throw new InvalidTargetException(text + " is in 0.0.0.0/8 ('this network')");
            if ("255.255.255.255".equals(text)) throw new InvalidTargetException(text + " is the limited broadcast address");
            if (first >= 240) throw new InvalidTargetException(text + " is in the reserved 240.0.0.0/4 block");
        }
    }
}
