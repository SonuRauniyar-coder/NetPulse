package com.netpulse.traceroute;

import java.util.List;

/**
 * One line of a trace: the routers that answered at this TTL.
 *
 * @param hop      TTL value, or 0 for a parser's continuation line that belongs to the previous hop
 * @param ip       first responder, or null when every probe timed out
 * @param rttsMs   one entry per probe; null means "*", no reply
 * @param otherIps further responders at the same TTL, which indicates load balancing (ECMP)
 * @param note     annotation such as "!H" (host unreachable), or null
 */
public record TracerouteHop(int hop, String ip, String hostname, List<Double> rttsMs, List<String> otherIps, String note) { }
