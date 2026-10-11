package io.floci.cli.output;

/**
 * The marks commands put in front of a line. Like {@link Ansi}, the choice is made once per
 * invocation: Unicode for a person at a terminal, plain ASCII with {@code --no-emoji}, when the
 * output is piped, or when {@code NO_COLOR} asks for undecorated output.
 */
public final class Glyphs {

    /** One set of marks. */
    public record Set(String ok, String warn, String fail, String arrow) {}

    public static final Set UNICODE = new Set("✓", "⚠", "✗", "→");
    public static final Set ASCII = new Set("+", "!", "x", "->");

    private static Set current = UNICODE;

    private Glyphs() {}

    /** Switches to plain ASCII for the rest of this invocation; it cannot be switched back. */
    public static void plain() {
        current = ASCII;
    }

    public static String ok()    { return current.ok(); }
    public static String warn()  { return current.warn(); }
    public static String fail()  { return current.fail(); }
    public static String arrow() { return current.arrow(); }

    public static boolean isPlain() { return current == ASCII; }
}
