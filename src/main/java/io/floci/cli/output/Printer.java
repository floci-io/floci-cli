package io.floci.cli.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.PrintStream;

public final class Printer {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .build());

    private final PrintStream out;
    private final PrintStream err;
    private final OutputFormat format;
    private final boolean quiet;

    public Printer(PrintStream out, PrintStream err, OutputFormat format, boolean quiet) {
        this.out = out;
        this.err = err;
        this.format = format;
        this.quiet = quiet;
    }

    public void println(String line) {
        if (!quiet) out.println(line);
    }

    public void print(String text) {
        if (!quiet) out.print(text);
    }

    public void error(String message) {
        err.println(Ansi.red("Error: ") + message);
    }

    /**
     * Reports {@code message} followed by {@code nextStep}, unless the message already carries its
     * own (a second line), as messages from DockerException or FlociException often do.
     */
    public void error(String message, String nextStep) {
        // A trailing newline is not a second line: forwarded output often ends with one.
        String text = message.stripTrailing();
        error(text.contains("\n") ? text : text + "\n" + nextStep);
    }

    public void warn(String message) {
        err.println(Ansi.yellow("Warning: ") + message);
    }

    public void structured(Object data) {
        try {
            switch (format) {
                case json -> out.println(JSON.writeValueAsString(data));
                case yaml -> out.print(YAML.writeValueAsString(data));
                default   -> out.println(data.toString());
            }
        } catch (Exception e) {
            error("Failed to serialize output: " + e.getMessage());
        }
    }

    public OutputFormat format() { return format; }
    public boolean isQuiet()     { return quiet; }
}
