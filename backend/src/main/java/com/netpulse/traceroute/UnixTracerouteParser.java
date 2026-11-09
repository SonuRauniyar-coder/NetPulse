package com.netpulse.traceroute;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Linux and macOS share one format:
 * <pre>
 *  1  gateway (192.168.1.1)  0.512 ms  0.401 ms  0.388 ms
 *  2  * * *
 *  3  a.example (10.0.0.1)  5.1 ms  b.example (10.0.0.2)  6.2 ms *
 *  4  10.9.9.9 (10.9.9.9)  7.1 ms !H
 * </pre>
 * Under load balancing, macOS moves further responders to an indented continuation line with no hop number.
 */
public class UnixTracerouteParser implements TracerouteParser {
    private static final Pattern HOP = Pattern.compile("^\\s*(\\d+)\\s+(.*)$");
    private static final Pattern CONTINUATION = Pattern.compile("^\\s+(\\S+\\s+\\([^)]+\\)\\s+.*)$");
    private static final Pattern ANNOTATION = Pattern.compile("^!\\S*$");

    @Override
    public Optional<TracerouteHop> parseLine(String line) {
        Matcher hop = HOP.matcher(line);
        if (hop.matches()) return Optional.of(parseBody(Integer.parseInt(hop.group(1)), hop.group(2)));
        Matcher more = CONTINUATION.matcher(line);
        if (more.matches()) return Optional.of(parseBody(0, more.group(1)));
        return Optional.empty();
    }

    private static TracerouteHop parseBody(int number, String body) {
        String[] t = body.trim().split("\\s+");
        String ip = null;
        String hostname = null;
        String note = null;
        List<Double> rtts = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (int i = 0; i < t.length; i++) {
            if (t[i].equals("*")) {
                rtts.add(null);
            } else if (i + 1 < t.length && t[i + 1].equals("ms") && isNumber(t[i])) {
                rtts.add(Double.parseDouble(t[i]));
                i++;
            } else if (ANNOTATION.matcher(t[i]).matches()) {
                note = t[i];
            } else {
                String name = t[i];
                String address = name;
                if (i + 1 < t.length && t[i + 1].startsWith("(") && t[i + 1].endsWith(")")) {
                    address = t[i + 1].substring(1, t[i + 1].length() - 1);
                    i++;
                }
                if (ip == null) {
                    ip = address;
                    hostname = name.equals(address) ? null : name;
                } else if (!address.equals(ip) && !others.contains(address)) {
                    others.add(address);
                }
            }
        }
        return new TracerouteHop(number, ip, hostname, rtts, others, note);
    }

    private static boolean isNumber(String s) {
        return s.matches("\\d+(\\.\\d+)?");
    }
}
