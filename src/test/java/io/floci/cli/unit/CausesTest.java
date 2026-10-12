package io.floci.cli.unit;

import io.floci.cli.util.Causes;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** BL-096: a cause is shown as one line, so the next step that follows it is not mistaken for part of it. */
class CausesTest {

    @Test
    void aParserMessageIsCutAtItsFirstLine() {
        IOException jackson = new IOException("Cannot deserialize value of type `java.lang.Integer`\n"
                + " at [Source: (File); line: 3, column: 7] (through reference chain: Profile[\"port\"])");

        assertEquals("Cannot deserialize value of type `java.lang.Integer`", Causes.firstLine(jackson));
    }

    @Test
    void aOneLineMessageIsKept() {
        assertEquals("Permission denied", Causes.firstLine(new IOException("Permission denied")));
        assertEquals("Permission denied", Causes.firstLine("  Permission denied\n"));
    }

    @Test
    void anExceptionWithoutAMessageIsNamedByItsType() {
        assertEquals("IOException", Causes.firstLine(new IOException()));
        assertEquals("IllegalStateException", Causes.firstLine(new IllegalStateException(" ")));
    }
}
