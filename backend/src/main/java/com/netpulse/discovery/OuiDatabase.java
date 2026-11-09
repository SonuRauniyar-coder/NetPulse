package com.netpulse.discovery;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * MAC vendor lookup from a bundled copy of the IEEE registry, so it works offline.
 *
 * <p>The first 3 octets of a MAC are the OUI, assigned to a manufacturer. IEEE also sells smaller blocks (28 and
 * 36 bits), so the longest prefix is tried first.
 *
 * <p>Bit 0x02 of the first octet marks a locally administered address. Phones and laptops use these as randomized
 * private MACs, and they carry no vendor information.
 */
@Component
public class OuiDatabase {
    static final String RANDOMIZED = "Locally administered (randomized)";
    private final Map<String, String> vendors = new HashMap<>(90_000);

    public OuiDatabase() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new ClassPathResource("oui/manuf.tsv.gz").getInputStream()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) vendors.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load the bundled OUI database", e);
        }
    }

    /** @param mac normalized form, AA:BB:CC:DD:EE:FF */
    public String lookup(String mac) {
        if (mac == null) return null;
        String hex = mac.replace(":", "");
        for (int len : new int[]{9, 7, 6}) {
            String vendor = vendors.get(hex.substring(0, len));
            if (vendor != null) return vendor;
        }
        return (Integer.parseInt(hex.substring(0, 2), 16) & 0x02) != 0 ? RANDOMIZED : null;
    }
}
