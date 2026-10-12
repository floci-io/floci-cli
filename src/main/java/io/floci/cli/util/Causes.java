package io.floci.cli.util;

/**
 * Turns an exception into the one line a user is shown. Parser errors from Jackson and YAML come
 * with a second line of source location ("at [Source: ...; line: 3]"), which is noise in a
 * message and, worse, looks like a next step to {@code Printer.error(message, nextStep)}.
 */
public final class Causes {

    private Causes() {}

    /** The first line of {@code e}'s message, or its type name when it has none. */
    public static String firstLine(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return firstLine(message);
    }

    /** The first non-empty line of {@code message}. */
    public static String firstLine(String message) {
        return message.strip().lines().findFirst().orElse(message).strip();
    }
}
