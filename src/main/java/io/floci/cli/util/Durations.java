package io.floci.cli.util;

/** Parses human-friendly duration strings like {@code 30s}, {@code 2m}, {@code 500ms}. */
public final class Durations {

    /**
     * Returns milliseconds; a bare number is seconds; null/blank defaults to 30s.
     *
     * @throws IllegalArgumentException for anything else, or a duration that is not positive, with
     *         a message suitable for printing to the user
     */
    public static long parseDuration(String s) {
        if (s == null || s.isBlank()) return 30_000;
        String value = s.trim().toLowerCase();
        long millis;
        try {
            if (value.endsWith("ms"))     millis = Long.parseLong(value.substring(0, value.length() - 2));
            else if (value.endsWith("s")) millis = Long.parseLong(value.substring(0, value.length() - 1)) * 1000;
            else if (value.endsWith("m")) millis = Long.parseLong(value.substring(0, value.length() - 1)) * 60_000;
            else if (value.endsWith("h")) millis = Long.parseLong(value.substring(0, value.length() - 1)) * 3_600_000;
            else                          millis = Long.parseLong(value) * 1000;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid duration '" + s + "'.\n"
                    + "Use a number with ms, s, m or h, for example 30s or 2m.");
        }
        if (millis <= 0) {
            throw new IllegalArgumentException("Duration '" + s + "' must be greater than zero.\n"
                    + "Use a positive value, for example 30s.");
        }
        return millis;
    }

    private Durations() {}
}
