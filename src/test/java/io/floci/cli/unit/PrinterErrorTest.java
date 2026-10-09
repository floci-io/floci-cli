package io.floci.cli.unit;

import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class PrinterErrorTest {

    private static String errorOf(String message, String nextStep) {
        Ansi.disable();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        new Printer(new PrintStream(new ByteArrayOutputStream()), new PrintStream(err), OutputFormat.text, false)
                .error(message, nextStep);
        return err.toString().strip();
    }

    @Test
    void theNextStepFollowsAOneLineMessage() {
        assertEquals("Error: Failed to stop container: boom\nCheck Docker.",
                errorOf("Failed to stop container: boom", "Check Docker."));
    }

    /** A message that already brings its own next step (a second line) is not given another. */
    @Test
    void aMessageWithItsOwnNextStepIsLeftAlone() {
        assertEquals("Error: Timed out.\nRe-run it.", errorOf("Timed out.\nRe-run it.", "Check Docker."));
    }
}
