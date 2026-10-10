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
        long amount;
        long unitMillis;
        try {
            if (value.endsWith("ms"))     { amount = number(value, 2); unitMillis = 1; }
            else if (value.endsWith("s")) { amount = number(value, 1); unitMillis = 1000; }
            else if (value.endsWith("m")) { amount = number(value, 1); unitMillis = 60_000; }
            else if (value.endsWith("h")) { amount = number(value, 1); unitMillis = 3_600_000; }
            else                          { amount = number(value, 0); unitMillis = 1000; }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid duration '" + s + "'.\n"
                    + "Use a number with ms, s, m or h, for example 30s or 2m.");
        }
        // Checked before the conversion: a product that overflows can come back positive.
        if (amount <= 0) {
            throw new IllegalArgumentException("Duration '" + s + "' must be greater than zero.\n"
                    + "Use a positive value, for example 30s.");
        }
        long millis;
        try {
            millis = Math.multiplyExact(amount, unitMillis);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Duration '" + s + "' is too long.\n"
                    + "Use a smaller value, for example 30s or 2m.");
        }
        return millis;
    }

    private static long number(String value, int suffixLength) {
        return Long.parseLong(value.substring(0, value.length() - suffixLength));
    }

    private Durations() {}
}
