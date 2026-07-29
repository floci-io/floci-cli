package io.floci.cli.util;

/** Parses human-friendly duration strings like {@code 30s}, {@code 2m}, {@code 500ms}. */
public final class Durations {

    /** Returns milliseconds; a bare number is seconds; null/blank defaults to 30s. */
    public static long parseDuration(String s) {
        if (s == null || s.isBlank()) return 30_000;
        s = s.trim().toLowerCase();
        if (s.endsWith("ms")) return Long.parseLong(s.substring(0, s.length() - 2));
        if (s.endsWith("s"))  return Long.parseLong(s.substring(0, s.length() - 1)) * 1000;
        if (s.endsWith("m"))  return Long.parseLong(s.substring(0, s.length() - 1)) * 60_000;
        if (s.endsWith("h"))  return Long.parseLong(s.substring(0, s.length() - 1)) * 3_600_000;
        return Long.parseLong(s) * 1000;
    }

    private Durations() {}
}
