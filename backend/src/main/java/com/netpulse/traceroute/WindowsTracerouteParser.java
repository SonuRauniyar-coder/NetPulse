package com.netpulse.traceroute;

import com.netpulse.common.net.IpLiterals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * tracert output is localized, so this matches structure rather than words:
 * <pre>
 *   1    &lt;1 ms    &lt;1 ms    &lt;1 ms  192.168.1.1
 *   2     *        *        *     Request timed out.
 *   3    12 ms    11 ms    13 ms  edge.example.net [203.0.113.9]
 * </pre>
 * "&lt;1 ms" is recorded as 1, the upper bound Windows reports.
 */
public class WindowsTracerouteParser implements TracerouteParser {
    private static final String RTT = "(<?\\d+\\s*ms|\\*)";
    private static final Pattern HOP = Pattern.compile("^\\s*(\\d+)\\s+" + RTT + "\\s+" + RTT + "\\s+" + RTT + "\\s+(.*)$");
    private static final Pattern NAMED = Pattern.compile("^(\\S+)\\s+\\[([^\\]]+)]");

    @Override
    public Optional<TracerouteHop> parseLine(String line) {
        Matcher m = HOP.matcher(line);
        if (!m.matches()) return Optional.empty();
        List<Double> rtts = new ArrayList<>();
        for (int g = 2; g <= 4; g++) rtts.add(parseRtt(m.group(g)));

        String rest = m.group(5).trim();
        String ip = null;
        String hostname = null;
        Matcher named = NAMED.matcher(rest);
        if (named.find()) {
            hostname = named.group(1);
            ip = named.group(2);
        } else {
            String first = rest.split("\\s+")[0];
            if (IpLiterals.isIpLiteral(first)) ip = first;   // otherwise it is the localized "Request timed out."
        }
        return Optional.of(new TracerouteHop(Integer.parseInt(m.group(1)), ip, hostname, rtts, List.of(), null));
    }

    private static Double parseRtt(String token) {
        return token.equals("*") ? null : Double.valueOf(token.replaceAll("[^0-9]", ""));
    }
}
