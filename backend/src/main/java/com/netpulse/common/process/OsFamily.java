package com.netpulse.common.process;

/**
 * Operating systems whose CLI networking tools we drive. They differ in flags AND output format,
 * so each one gets its own command builder and parser.
 */
public enum OsFamily {
    LINUX, MACOS, WINDOWS;

    public static OsFamily detect() {
        return fromOsName(System.getProperty("os.name", ""));
    }

    static OsFamily fromOsName(String osName) {
        String n = osName.toLowerCase();
        if (n.startsWith("windows")) return WINDOWS;
        if (n.startsWith("mac") || n.contains("darwin")) return MACOS;
        return LINUX; // Linux and other Unix-likes using iputils-style tools
    }
}
