package com.netpulse.monitoring.ping;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared logic for the two Unix-style formats. Only lines with a "time=" value count as replies:
 * an ICMP error line such as "92 bytes from 10.0.0.1: Destination Host Unreachable" also starts with
 * "N bytes from" but is not an echo reply.
 */
abstract class UnixPingParser implements PingParser {
    private static final Pattern REPLY = Pattern.compile("^\\d+ bytes from .*\\btime[=<]\\s*([0-9.]+)\\s*ms", Pattern.MULTILINE);
    private static final Pattern SENT = Pattern.compile("(\\d+) packets transmitted");

    @Override
    public PingOutcome parse(String output, int requestedCount) {
        List<Double> rtts = new ArrayList<>();
        Matcher m = REPLY.matcher(output);
        while (m.find()) {
            int lineEnd = output.indexOf('\n', m.end());
            String rest = output.substring(m.end(), lineEnd < 0 ? output.length() : lineEnd);
            if (!rest.contains("DUP!")) {   // a duplicated reply must not inflate the received count
                rtts.add(Double.parseDouble(m.group(1)));
            }
        }
        Matcher s = SENT.matcher(output);
        int sent = s.find() ? Integer.parseInt(s.group(1)) : requestedCount;
        if (!rtts.isEmpty()) {
            return new PingOutcome(sent, rtts.size(), rtts, null, null);
        }
        PingErrorCategory category = classify(output);
        return PingOutcome.failure(category, describe(category, output), sent);
    }

    private PingErrorCategory classify(String output) {
        String o = output.toLowerCase();
        if (o.contains("operation not permitted") || o.contains("permission denied") || o.contains("must be root")) {
            return PingErrorCategory.PERMISSION_DENIED;
        }
        if (isDnsFailure(o)) return PingErrorCategory.DNS_FAILURE;
        if (o.contains("unreachable") || o.contains("no route to host")) return PingErrorCategory.HOST_UNREACHABLE;
        return PingErrorCategory.TIMEOUT;
    }

    protected abstract boolean isDnsFailure(String lowerCaseOutput);

    private static String describe(PingErrorCategory c, String output) {
        return switch (c) {
            case TIMEOUT -> "No echo replies received";
            default -> firstInterestingLine(output);
        };
    }

    private static String firstInterestingLine(String output) {
        return output.lines().map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("PING") && !l.startsWith("---"))
                .findFirst().map(l -> l.length() > 200 ? l.substring(0, 200) : l).orElse("ping failed");
    }
}
