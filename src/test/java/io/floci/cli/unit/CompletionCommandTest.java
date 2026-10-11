package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** BL-054: completion offers the shells it can generate a script for, and only those. */
class CompletionCommandTest {

    @TempDir
    Path tempDir;

    private record Run(int exit, String out, String err) {}

    private Run run(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            int exit = FlociCli.buildCommandLine(new ProfileStore(tempDir)).execute(args);
            return new Run(exit, outBuf.toString(), errBuf.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    @Test
    void theHelpListsOnlyTheShellsThatWork() {
        CommandLine completion = FlociCli.buildCommandLine(new ProfileStore(tempDir)).getSubcommands().get("completion");

        assertEquals("bash|zsh", completion.getCommandSpec().positionalParameters().get(0).paramLabel());
        String usage = completion.getUsageMessage(CommandLine.Help.Ansi.OFF);
        assertFalse(usage.contains("fish"), usage);
        assertFalse(usage.contains("powershell"), usage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bash", "zsh", "BASH"})
    void aSupportedShellGetsAScript(String shell) {
        Run r = run("completion", shell);

        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("floci"), "a completion script for the floci command");
        assertEquals("", r.err());
    }

    /** A shell there is no script for is a usage error, and nothing is written to stdout to be sourced. */
    @ParameterizedTest
    @ValueSource(strings = {"fish", "powershell", "nushell"})
    void anUnsupportedShellIsAUsageError(String shell) {
        Run r = run("completion", shell);

        assertEquals(2, r.exit());
        assertEquals("", r.out());
        assertTrue(r.err().contains("Shell '" + shell + "' is not supported."), r.err());
        assertTrue(r.err().contains("Use bash or zsh."), r.err());
    }
}
