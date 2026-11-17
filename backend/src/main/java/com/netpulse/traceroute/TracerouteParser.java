package com.netpulse.traceroute;

import java.util.Optional;

public interface TracerouteParser {
    /** @return the parsed hop, or empty for lines that are not hops (headers, warnings) */
    Optional<TracerouteHop> parseLine(String line);
}
