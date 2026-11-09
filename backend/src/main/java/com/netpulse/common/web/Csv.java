package com.netpulse.common.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class Csv {
    private Csv() { }

    /**
     * RFC 4180 quoting, plus a guard against CSV injection: spreadsheets execute cells that begin with
     * = + - @ as formulas, and a device name or reason could contain one. A leading apostrophe makes it text.
     */
    public static String cell(Object value) {
        if (value == null) return "";
        String s = value.toString();
        if (value instanceof CharSequence && !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return s.matches("(?s).*[\",\r\n].*") ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    public static String row(Object... cells) {
        return Arrays.stream(cells).map(Csv::cell).collect(Collectors.joining(",")) + "\r\n";
    }

    public static ResponseEntity<byte[]> download(String filename, String header, List<String> rows) {
        StringBuilder sb = new StringBuilder(header);
        rows.forEach(sb::append);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
