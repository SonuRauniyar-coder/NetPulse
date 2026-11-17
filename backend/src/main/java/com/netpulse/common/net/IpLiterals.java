package com.netpulse.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Strict parsing of IP literals WITHOUT touching DNS.
 *
 * <p>Why not just {@code InetAddress.getByName(input)}? Because when the input is not a valid literal it
 * silently performs a DNS lookup. For validation we want a pure syntax check.
 */
public final class IpLiterals {
    private IpLiterals() { }

    // 0-255, no leading zeros (leading zeros are ambiguous: some tools read "010" as octal 8)
    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile("^" + OCTET + "(\\." + OCTET + "){3}$");
    private static final Pattern HEX_GROUP = Pattern.compile("^[0-9A-Fa-f]{1,4}$");

    public static boolean isIpv4(String s) {
        return s != null && IPV4.matcher(s).matches();
    }

    /** RFC 4291 text form: 8 groups, or fewer with exactly one "::", optional trailing dotted IPv4. No zone ids. */
    public static boolean isIpv6(String s) {
        if (s == null || s.length() < 2 || s.length() > 45 || !s.contains(":")) return false;
        int dbl = s.indexOf("::");
        if (dbl != s.lastIndexOf("::")) return false;          // "::" may appear only once
        if (s.contains(":::")) return false;

        String[] parts = s.split(":", -1);
        int groups = 0;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;                          // produced by "::" or a leading/trailing ':'
            if (i == parts.length - 1 && p.contains(".")) {     // embedded IPv4, e.g. ::ffff:192.0.2.1
                if (!isIpv4(p)) return false;
                groups += 2;
            } else if (HEX_GROUP.matcher(p).matches()) {
                groups++;
            } else {
                return false;
            }
        }
        // a single leading or trailing ':' is only allowed as part of "::"
        if (s.startsWith(":") && !s.startsWith("::")) return false;
        if (s.endsWith(":") && !s.endsWith("::")) return false;
        return dbl >= 0 ? groups <= 7 : groups == 8;
    }

    public static boolean isIpLiteral(String s) {
        return isIpv4(s) || isIpv6(s);
    }

    /** Parses a literal that already passed {@link #isIpLiteral}; no DNS lookup can happen for valid literals. */
    public static Optional<InetAddress> parse(String s) {
        if (!isIpLiteral(s)) return Optional.empty();
        try {
            return Optional.of(InetAddress.getByName(s));
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }
}
