package io.floci.cli.unit;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

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

    /** A trailing newline is not a second line, so the next step still follows. */
    @Test
    void aTrailingNewlineDoesNotHideTheNextStep() {
        assertEquals("Error: Failed to stop container: boom\nCheck Docker.",
                errorOf("Failed to stop container: boom\n", "Check Docker."));
    }

    /** Docker's own output never counts as advice, whether it ends in a newline or spans several lines. */
    @Test
    void dockersOwnOutputAlwaysGetsTheNextStep() {
        assertEquals("Error response from daemon: boom\nCheck Docker.",
                DockerException.ofOutput("Error response from daemon: boom\n").withNextStep("Check Docker."));
        assertEquals("Error response from daemon: boom\nSee 'docker run --help'.\nCheck Docker.",
                DockerException.ofOutput("Error response from daemon: boom\nSee 'docker run --help'.\n")
                        .withNextStep("Check Docker."));
    }

    /** An error in the CLI's own words that already says what to do is not given a second next step. */
    @Test
    void anErrorWithItsOwnAdviceKeepsIt() {
        assertEquals("'docker stop' did not finish within 30s.\nCheck that the Docker daemon is responsive.",
                new DockerException("'docker stop' did not finish within 30s.\nCheck that the Docker daemon is responsive.")
                        .withNextStep("Check Docker."));
        assertEquals("Interrupted\nCheck Docker.", new DockerException("Interrupted").withNextStep("Check Docker."));
    }

    /** Through the real docker call: stderr ending in a newline reaches the user with the next step. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFailedDockerCallCarriesTheNextStep(@TempDir Path dir) throws Exception {
        Path fakeDocker = dir.resolve("docker");
        Files.writeString(fakeDocker, "#!/bin/sh\necho 'Error response from daemon: cannot stop container' >&2\nexit 1\n");
        assertTrue(fakeDocker.toFile().setExecutable(true));

        DockerException e = assertThrows(DockerException.class,
                () -> new DockerClient(fakeDocker.toString()).stopContainer("floci", 1));

        assertEquals("Error response from daemon: cannot stop container\n" + DockerClient.CHECK_DOCKER,
                e.withNextStep(DockerClient.CHECK_DOCKER));
    }
}
