package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BL-067: the rows of README's "Exit Codes" section that need neither Docker nor a server. They
 * are the ones a reader is most likely to get wrong: which rejected values are 2 and which are 1.
 */
class ExitCodeTableTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({
            // The command line could not be used.
            "2, status --no-such-flag",
            "2, no-such-command",
            "2, status --profile no-such-profile",
            "2, start --port abc",
            "2, wait --timeout abc",
            "2, wait --timeout 0s",
            "2, start --port 70000",
            "2, start --pull sometimes",
            "2, doctor --check no.such.check",
            "2, env --shell tcsh",
            "2, config profile show ../escape",
            // Accepted by the command line, rejected by the command with 1.
            "1, update --version not-a-version",
            "1, config profile create demo --port 0",
            "1, config profile show no-such-profile",
            // Nothing wrong.
            "0, --version",
            "0, config profile list"})
    void theDocumentedCode(int expected, String commandLine) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        // Commands that manage profiles build their own store under the home directory. It is
        // pointed at the temporary one, so the developer's saved profiles cannot change a result.
        String home = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuf));
        int exit;
        try {
            ProfileStore store = new ProfileStore(tempDir.resolve(".floci").resolve("profiles"));
            exit = FlociCli.buildCommandLine(store).execute(commandLine.split(" "));
        } finally {
            System.setOut(out);
            System.setErr(err);
            System.setProperty("user.home", home);
        }

        assertEquals(expected, exit, commandLine + "\n" + errBuf);
    }

    /** The guard on the test itself: a profile saved in the real home directory is not visible here. */
    @Test
    void profileCommandsSeeOnlyTheTemporaryHome() throws Exception {
        Path profiles = Files.createDirectories(tempDir.resolve(".floci").resolve("profiles"));
        Files.writeString(profiles.resolve("only-here.yaml"), "port: 4599\n");

        theDocumentedCode(0, "config profile show only-here");
        theDocumentedCode(1, "config profile show not-here");
    }
}
