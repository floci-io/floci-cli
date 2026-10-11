package io.floci.cli.unit;

import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** BL-074: where each kind of output goes, and how structured output is rendered. */
class PrinterTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeAll
    static void plainText() {
        Ansi.disable();
    }

    private Printer printer(OutputFormat format, boolean quiet) {
        return new Printer(new PrintStream(out), new PrintStream(err), format, quiet);
    }

    private static Map<String, Object> sample() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "running");
        data.put("services", List.of("s3", "sqs"));
        return data;
    }

    @Test
    void textGoesToStdoutAndProblemsToStderr() {
        Printer p = printer(OutputFormat.text, false);

        p.println("hello");
        p.error("broken\nRun 'floci doctor'.");
        p.warn("careful");

        // println ends a line the platform's way; the newline inside the message is the caller's.
        String newline = System.lineSeparator();
        assertEquals("hello" + newline, out.toString());
        assertEquals("Error: broken\nRun 'floci doctor'." + newline + "Warning: careful" + newline, err.toString());
    }

    /** --quiet silences ordinary output, never errors or warnings. */
    @Test
    void quietKeepsErrorsAndWarnings() {
        Printer p = printer(OutputFormat.text, true);

        p.println("hello");
        p.print("partial");
        p.error("broken\nFix it.");
        p.warn("careful");

        assertEquals("", out.toString());
        assertTrue(err.toString().contains("Error: broken"));
        assertTrue(err.toString().contains("Warning: careful"));
    }

    @Test
    void jsonIsIndentedAndKeepsKeyOrder() {
        printer(OutputFormat.json, false).structured(sample());

        String json = out.toString();
        assertTrue(json.startsWith("{" + System.lineSeparator()), json);
        assertTrue(json.indexOf("\"status\"") < json.indexOf("\"services\""), json);
        assertTrue(json.contains("\"s3\", \"sqs\"") || json.contains("\"s3\",\"sqs\""), json);
    }

    @Test
    void yamlHasNoDocumentMarker() {
        printer(OutputFormat.yaml, false).structured(sample());

        assertEquals("status: \"running\"\nservices:\n- \"s3\"\n- \"sqs\"\n", out.toString());
    }

    /** Structured output is the command's result, so --quiet does not hide it. */
    @Test
    void quietStillPrintsStructuredOutput() {
        printer(OutputFormat.json, true).structured(Map.of("ok", true));

        assertTrue(out.toString().contains("\"ok\" : true"), out.toString());
    }
}
