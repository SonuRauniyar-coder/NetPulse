package com.netpulse.monitoring.ping;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows ping. Two traps make this parser different from the Unix ones:
 *
 * <ol>
 *   <li><b>Localization.</b> Output is translated with the OS language ("Reply from" / "Antwort von",
 *       "time=" / "Zeit="), and unlike Unix it cannot be forced to English via LC_ALL. So we match the
 *       language-independent shape: an IPv4 echo reply is the line containing {@code TTL=}, and the RTT is the
 *       number before "ms". IPv6 replies have no TTL field, so they are recognised by the "ms" value.</li>
 *   <li><b>"Received" lies.</b> When a router returns "Destination host unreachable", Windows counts that
 *       response as Received, printing "Received = 4, Lost = 0 (0% loss)" for a host that never answered.
 *       That's why received is counted from real echo replies and never taken from the summary.</li>
 * </ol>
 */
public class WindowsPingParser implements PingParser {
    // "time=14ms", "time<1ms", "Zeit=14ms". Windows reports sub-millisecond replies as "<1ms", recorded as 1 ms.
    private static final Pattern RTT = Pattern.compile("[=<]\\s*(\\d+)\\s*ms\\b", Pattern.CASE_INSENSITIVE);
    // "Packets: Sent = 4, Received = 3, Lost = 1" in any language: three "word = number" pairs
    private static final Pattern SUMMARY = Pattern.compile("=\\s*(\\d+),[^=]*=\\s*(\\d+),[^=]*=\\s*(\\d+)");
    // "Reply from 192.168.1.1: Destination host unreachable." - an address, a colon, then no '=' at all
    private static final Pattern ERROR_FROM_ROUTER = Pattern.compile("^.*\\s[0-9a-fA-F:.]+%?\\d*:\\s[^=]+$");

    @Override
    public PingOutcome parse(String output, int requestedCount) {
        List<Double> rtts = new ArrayList<>();
        boolean routerError = false;
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || isStatisticsLine(line)) continue;
            Matcher m = RTT.matcher(line);
            if (m.find() && line.contains(":")) {
                rtts.add(Double.parseDouble(m.group(1)));
            } else if (ERROR_FROM_ROUTER.matcher(line).matches()) {
                routerError = true;
            }
        }
        Matcher s = SUMMARY.matcher(output);
        int sent = s.find() ? Integer.parseInt(s.group(1)) : requestedCount;

        if (!rtts.isEmpty()) return new PingOutcome(sent, rtts.size(), rtts, null, null);

        String lower = output.toLowerCase();
        if (lower.contains("could not find host")) {
            return PingOutcome.failure(PingErrorCategory.DNS_FAILURE, "Could not find host", sent);
        }
        if (lower.contains("access denied") || lower.contains("administrator")) {
            return PingOutcome.failure(PingErrorCategory.PERMISSION_DENIED, "Access denied", sent);
        }
        if (routerError || lower.contains("unreachable")) {
            return PingOutcome.failure(PingErrorCategory.HOST_UNREACHABLE, "Destination host unreachable", sent);
        }
        return PingOutcome.failure(PingErrorCategory.TIMEOUT, "No echo replies received", sent);
    }

    /** Skips the "Minimum = 12ms, Maximum = 16ms, Average = 14ms" line: a reply line only ever has one "ms" value. */
    private static boolean isStatisticsLine(String line) {
        return RTT.matcher(line).results().count() >= 2;
    }
}
