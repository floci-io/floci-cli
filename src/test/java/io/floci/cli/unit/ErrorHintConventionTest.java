package io.floci.cli.unit;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGENTS.md: every {@code printer.error(...)} ends with a suggested next step. A call that writes
 * its own text must either put a second line in it or pass the next step as a second argument;
 * a call that only forwards a message (a variable or {@code e.getMessage()}) is trusted to forward
 * one that already has it. {@code e.withNextStep(...)} on a DockerException is the third way: the
 * exception knows whether its text is docker's raw output or already carries advice.
 */
class ErrorHintConventionTest {

    @Test
    void everyErrorWithItsOwnTextEndsWithANextStep() throws Exception {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file);
                int at = 0;
                while ((at = src.indexOf("printer.error(", at)) >= 0) {
                    int start = at + "printer.error(".length();
                    String args = argumentsFrom(src, start);
                    if (args.contains("\"") && !args.contains("\\n") && !hasTopLevelComma(args)
                            && !args.contains(".withNextStep(")) {
                        int line = (int) src.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
                        missing.add(file + ":" + line);
                    }
                    at = start;
                }
            }
        }
        assertEquals(List.of(), missing, "error messages without a next step");
    }

    // The text between the opening parenthesis at 'start' and its matching close, string-aware.
    private static String argumentsFrom(String src, int start) {
        int depth = 1;
        int i = start;
        while (depth > 0) {
            char c = src.charAt(i);
            if (c == '"') {
                i++;
                while (src.charAt(i) != '"') i += src.charAt(i) == '\\' ? 2 : 1;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            i++;
        }
        return src.substring(start, i - 1);
    }

    private static boolean hasTopLevelComma(String args) {
        String noStrings = args.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
        int depth = 0;
        for (char c : noStrings.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return true;
        }
        return false;
    }
}
