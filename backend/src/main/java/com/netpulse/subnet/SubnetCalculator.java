package com.netpulse.subnet;

import com.netpulse.common.error.ApiException;
import com.netpulse.common.net.IpLiterals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * IPv4 subnetting in plain arithmetic. An address is a 32-bit number, held in a long so it stays unsigned.
 *
 * <pre>
 * mask      = prefix one-bits followed by zeros      /26 -> 255.255.255.192
 * wildcard  = NOT mask                                     0.0.0.63
 * network   = ip AND mask                            192.168.10.77 -> 192.168.10.64
 * broadcast = network OR wildcard                          192.168.10.127
 * usable    = 2^(32-prefix) - 2                      minus the network and broadcast addresses
 * </pre>
 *
 * <p>Two prefixes are exceptions. A /31 (RFC 3021) is a point-to-point link: it has no network or broadcast
 * address, so both addresses are usable. A /32 identifies one host, typically a loopback or host route.
 */
public final class SubnetCalculator {
    private static final long ALL_ONES = 0xFFFFFFFFL;

    private SubnetCalculator() { }

    public record Cidr(long ip, int prefix) {
        public long mask() { return mask(prefix); }
        public long network() { return ip & mask(); }
        public long size() { return 1L << (32 - prefix); }
        static long mask(int prefix) { return prefix == 0 ? 0 : (ALL_ONES << (32 - prefix)) & ALL_ONES; }
        /** The canonical network form, for example 192.168.10.64/26. */
        @Override public String toString() { return format(network()) + "/" + prefix; }
    }

    public record BinaryView(String address, String mask, String network, String broadcast) { }

    /** broadcast is null for /31 and /32, which have none. */
    public record SubnetInfo(String input, String cidr, String address, int prefix, String network, String broadcast,
                             String firstHost, String lastHost, long totalAddresses, long usableHosts, String subnetMask,
                             String wildcardMask, String addressScope, String historicalClass, BinaryView binary, String note) { }

    public record Requirement(String name, long hosts) { }

    public record Allocation(String name, long requestedHosts, String cidr, String network, String firstHost, String lastHost,
                             String broadcast, String subnetMask, long usableHosts, long wastedHosts) { }

    public record VlsmResult(String baseCidr, long totalAddresses, long allocatedAddresses, long freeAddresses, List<Allocation> allocations) { }

    public static Cidr parse(String text) {
        String s = text == null ? "" : text.trim();
        int slash = s.indexOf('/');
        if (slash < 0) throw ApiException.badRequest("Use CIDR notation, for example 192.168.1.0/24");
        String ip = s.substring(0, slash);
        String prefix = s.substring(slash + 1);
        if (!IpLiterals.isIpv4(ip)) throw ApiException.badRequest("'" + ip + "' is not a valid IPv4 address");
        if (!prefix.matches("\\d{1,2}") || Integer.parseInt(prefix) > 32) throw ApiException.badRequest("Prefix must be between 0 and 32");
        return new Cidr(toLong(ip), Integer.parseInt(prefix));
    }

    public static SubnetInfo calculate(String text) {
        Cidr c = parse(text);
        long network = c.network();
        long wildcard = ~c.mask() & ALL_ONES;
        long last = network | wildcard;
        boolean noBroadcast = c.prefix() >= 31;
        long firstHost = noBroadcast ? network : network + 1;
        long lastHost = noBroadcast ? last : last - 1;
        String note = switch (c.prefix()) {
            case 31 -> "/31 point-to-point link (RFC 3021): both addresses are usable and there is no broadcast address.";
            case 32 -> "/32 identifies a single host, as used for loopbacks and host routes.";
            default -> c.ip() != network ? format(c.ip()) + " is a host inside this network." : null;
        };
        return new SubnetInfo(text.trim(), c.toString(), format(c.ip()), c.prefix(), format(network), noBroadcast ? null : format(last),
                format(firstHost), format(lastHost), c.size(), usable(c.prefix()), format(c.mask()), format(wildcard),
                scope(c.ip()), historicalClass(c.ip()),
                new BinaryView(binary(c.ip()), binary(c.mask()), binary(network), noBroadcast ? null : binary(last)), note);
    }

    public static long usable(int prefix) {
        return switch (prefix) {
            case 32 -> 1;
            case 31 -> 2;
            default -> (1L << (32 - prefix)) - 2;
        };
    }

    /** Every address a host could have: excludes the network and broadcast addresses unless the prefix is /31 or /32. */
    public static List<Long> hostAddresses(Cidr c) {
        long first = c.prefix() >= 31 ? c.network() : c.network() + 1;
        long last = c.prefix() >= 31 ? c.network() + c.size() - 1 : c.network() + c.size() - 2;
        List<Long> out = new ArrayList<>((int) (last - first + 1));
        for (long a = first; a <= last; a++) out.add(a);
        return out;
    }

    /**
     * VLSM: give each segment the smallest subnet that fits, largest segment first.
     *
     * <p>Largest-first is what keeps this simple. Every block size is a power of two, and a block must start on a
     * multiple of its own size. Allocating in descending size means the next free address is always aligned for
     * the next block, so no space is lost to gaps.
     *
     * <p>Sizing always reserves network and broadcast, so a request for 2 hosts gets a /30, as CCNA exercises expect.
     */
    public static VlsmResult vlsm(String base, List<Requirement> requirements) {
        Cidr c = parse(base);
        if (requirements.isEmpty()) throw ApiException.badRequest("Add at least one segment");
        List<Requirement> sorted = requirements.stream().sorted(Comparator.comparingLong(Requirement::hosts).reversed()).toList();

        long cursor = c.network();
        long end = c.network() + c.size();
        List<Allocation> out = new ArrayList<>();
        for (Requirement r : sorted) {
            if (r.hosts() < 1) throw ApiException.badRequest("'" + r.name() + "' must need at least 1 host");
            int bits = 2;
            while (bits < 32 && (1L << bits) - 2 < r.hosts()) bits++;
            if ((1L << bits) - 2 < r.hosts()) throw ApiException.badRequest("'" + r.name() + "' needs more hosts than IPv4 can hold");
            int prefix = 32 - bits;
            long size = 1L << bits;
            if (prefix < c.prefix() || cursor + size > end) {
                throw ApiException.badRequest("'" + r.name() + "' (" + r.hosts() + " hosts, needs a /" + prefix + ") does not fit in the "
                        + (end - cursor) + " addresses left in " + c);
            }
            long usable = size - 2;
            out.add(new Allocation(r.name(), r.hosts(), format(cursor) + "/" + prefix, format(cursor), format(cursor + 1),
                    format(cursor + size - 2), format(cursor + size - 1), format(Cidr.mask(prefix)), usable, usable - r.hosts()));
            cursor += size;
        }
        long allocated = cursor - c.network();
        return new VlsmResult(c.toString(), c.size(), allocated, c.size() - allocated, out);
    }

    /** Scope per the IANA special-purpose registry (RFC 6890). Most specific match wins. */
    public static String scope(long ip) {
        record Block(String cidr, String label) { }
        List<Block> blocks = List.of(
                new Block("0.0.0.0/8", "Special: this network"),
                new Block("10.0.0.0/8", "Private (RFC 1918)"),
                new Block("100.64.0.0/10", "Special: shared CGNAT space (RFC 6598)"),
                new Block("127.0.0.0/8", "Special: loopback"),
                new Block("169.254.0.0/16", "Special: link-local"),
                new Block("172.16.0.0/12", "Private (RFC 1918)"),
                new Block("192.0.0.0/24", "Special: IETF protocol assignments"),
                new Block("192.0.2.0/24", "Special: documentation (TEST-NET-1)"),
                new Block("192.88.99.0/24", "Special: deprecated 6to4 relay"),
                new Block("192.168.0.0/16", "Private (RFC 1918)"),
                new Block("198.18.0.0/15", "Special: benchmarking"),
                new Block("198.51.100.0/24", "Special: documentation (TEST-NET-2)"),
                new Block("203.0.113.0/24", "Special: documentation (TEST-NET-3)"),
                new Block("224.0.0.0/4", "Special: multicast"),
                new Block("240.0.0.0/4", "Special: reserved"));
        for (Block b : blocks) {
            Cidr c = parse(b.cidr());
            if ((ip & c.mask()) == c.network()) return b.label();
        }
        return "Public";
    }

    /** Classful addressing ended with CIDR in 1993. Shown for education only. */
    static String historicalClass(long ip) {
        int first = (int) (ip >>> 24);
        if (first < 128) return "A";
        if (first < 192) return "B";
        if (first < 224) return "C";
        return first < 240 ? "D" : "E";
    }

    public static long toLong(String ipv4) {
        long v = 0;
        for (String part : ipv4.split("\\.")) v = (v << 8) | Integer.parseInt(part);
        return v;
    }

    public static String format(long ip) {
        return ((ip >>> 24) & 255) + "." + ((ip >>> 16) & 255) + "." + ((ip >>> 8) & 255) + "." + (ip & 255);
    }

    private static String binary(long ip) {
        String bits = String.format("%32s", Long.toBinaryString(ip & ALL_ONES)).replace(' ', '0');
        return bits.substring(0, 8) + "." + bits.substring(8, 16) + "." + bits.substring(16, 24) + "." + bits.substring(24);
    }
}
