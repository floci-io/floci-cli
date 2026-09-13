package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.config.ProfileNotFoundException;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A profile that does not resolve must stop the command rather than silently fall back to the
 * built-in defaults — silently falling back is what made issue #22 lose data unnoticed.
 */
class MissingProfileTest {

    @TempDir
    Path tempDir;

    private CommandLine cli() {
        return FlociCli.buildCommandLine(new ProfileStore(tempDir));
    }

    private void writeProfile(String name, String yaml) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve(name + ".yaml"), yaml);
    }

    private static String captureStderr(Supplier<Integer> action, int[] exitCode) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));
        try {
            exitCode[0] = action.get();
        } finally {
            System.setErr(original);
        }
        return captured.toString();
    }

    @Test
    void unknownProfileExitsTwoWithAMessageThatNamesTheNextStep() {
        int[] exit = new int[1];
        String stderr = captureStderr(() -> cli().execute("start", "--profile", "typo"), exit);

        assertEquals(2, exit[0]);
        assertTrue(stderr.contains("Profile 'typo' not found"), stderr);
        assertTrue(stderr.contains(tempDir.toString()), stderr);
        assertTrue(stderr.contains("floci config profile list"), stderr);
        // The message stands alone; picocli's usage dump would bury it.
        assertFalse(stderr.contains("Usage:"), stderr);
    }

    @Test
    void failsEvenWhenEveryProfileBackedOptionWasPassedExplicitly() {
        int[] exit = new int[1];
        captureStderr(() -> cli().execute("start", "--profile", "typo",
                "--endpoint", "http://x:1", "--container", "c", "--image", "i",
                "--port", "1", "--persist", "/p", "--services", "s", "-o", "json"), exit);

        assertEquals(2, exit[0]);
    }

    @Test
    void helpStillWorksWithABadProfile() {
        int[] exit = new int[1];
        captureStderr(() -> cli().execute("start", "--profile", "typo", "--help"), exit);

        assertEquals(0, exit[0]);
    }

    @Test
    void traversalInTheProfileNameIsRejected() {
        int[] exit = new int[1];
        String stderr = captureStderr(() -> cli().execute("start", "--profile", "../../escape"), exit);

        assertEquals(2, exit[0]);
        assertTrue(stderr.contains("Invalid profile name"), stderr);
    }

    @Test
    void unusableProfileValuesNameTheFileRatherThanAnOptionTheUserNeverTyped() throws Exception {
        writeProfile("bad-output", "output: bogus\n");
        writeProfile("bad-port", "port: 99999\n");

        int[] exit = new int[1];
        String outputErr = captureStderr(() -> cli().execute("start", "--profile", "bad-output"), exit);
        assertEquals(2, exit[0]);
        assertTrue(outputErr.contains("bad-output.yaml"), outputErr);
        assertTrue(outputErr.contains("output must be text, json or yaml"), outputErr);

        String portErr = captureStderr(() -> cli().execute("start", "--profile", "bad-port"), exit);
        assertEquals(2, exit[0]);
        assertTrue(portErr.contains("bad-port.yaml"), portErr);
        assertTrue(portErr.contains("port must be between 1 and 65535"), portErr);
    }

    @Test
    void parsingRaisesTheDedicatedException() {
        assertThrows(ProfileNotFoundException.class,
                () -> cli().parseArgs("start", "--profile", "typo"));
    }
}
