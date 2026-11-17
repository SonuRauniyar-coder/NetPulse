package com.netpulse.discovery;

import com.netpulse.common.net.IpLiterals;
import com.netpulse.common.process.OsFamily;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the OS neighbor table: the IP-to-MAC mappings that ARP has learned.
 *
 * <p>ARP works only on the local broadcast domain, which is why this can't see beyond the first router. The ping
 * sweep is what fills the table: to send any packet to a local address, the OS must first ARP for it.
 *
 * <pre>
 * Linux   192.168.1.1 dev eth0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
 *         192.168.1.9 dev eth0  FAILED                              (no lladdr: no answer to ARP)
 * macOS   ? (192.168.1.1) at aa:bb:cc:d:e:f on en0 ifscope [ethernet]   (octets are not zero-padded)
 *         ? (192.168.1.9) at (incomplete) on en0 ifscope [ethernet]
 * Windows   192.168.1.1           aa-bb-cc-dd-ee-ff     dynamic     (the last word is localized, so it is ignored)
 * </pre>
 */
public final class NeighborTableParser {
    private static final Pattern LINUX = Pattern.compile("^(\\S+)\\s+dev\\s+\\S+\\s+lladdr\\s+([0-9a-fA-F:]+)\\b.*$");
    private static final Pattern MAC_OS = Pattern.compile("^\\S+\\s+\\((\\S+)\\)\\s+at\\s+([0-9a-fA-F:]+)\\s.*$");
    private static final Pattern WINDOWS = Pattern.compile("^\\s*(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+([0-9a-fA-F]{2}(?:-[0-9a-fA-F]{2}){5})\\s+.*$");

    private NeighborTableParser() { }

    public static List<String> command(OsFamily os) {
        return switch (os) {
            case LINUX -> List.of("ip", "-4", "neigh", "show");
            case MACOS -> List.of("arp", "-an");
            case WINDOWS -> List.of("arp", "-a");
        };
    }

    /** @return IPv4 address to normalized MAC, leaving out unresolved, broadcast and multicast entries */
    public static Map<String, String> parse(OsFamily os, String output) {
        Pattern p = switch (os) {
            case LINUX -> LINUX;
            case MACOS -> MAC_OS;
            case WINDOWS -> WINDOWS;
        };
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            Matcher m = p.matcher(line);
            if (!m.matches() || !IpLiterals.isIpv4(m.group(1))) continue;
            String mac = normalizeMac(m.group(2));
            if (mac != null && !isGroupAddress(mac)) out.put(m.group(1), mac);
        }
        return out;
    }

    /** @return AA:BB:CC:0D:0E:0F, or null if this isn't six octets */
    static String normalizeMac(String raw) {
        String[] parts = raw.split("[:-]");
        if (parts.length != 6) return null;
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 2) return null;
            if (i > 0) sb.append(':');
            sb.append(parts[i].length() == 1 ? "0" : "").append(parts[i].toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** The lowest bit of the first octet marks a group address. Broadcast (FF:FF:...) is one too. */
    private static boolean isGroupAddress(String mac) {
        return (Integer.parseInt(mac.substring(0, 2), 16) & 1) == 1;
    }
}
