package io.floci.cli.unit;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.RestartCommand;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Restart builds its StartCommand programmatically, so picocli never applies the profile to it.
 * Without the hand-off in buildStartCommand(), 'restart --profile x' would drop the persistence
 * directory that 'start --profile x' honours.
 */
class RestartCommandTest {

    private static final List<String> SOCKET = List.of("-v", "/sock:/sock");

    @TempDir
    Path tempDir;

    private RestartCommand parse(ProductProfile product, String... args) {
        RestartCommand restart = new RestartCommand(product, new ProfileStore(tempDir));
        new CommandLine(restart).parseArgs(args);
        return restart;
    }

    private void writeProfile(String name, String yaml) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve(name + ".yaml"), yaml);
    }

    @Test
    void carriesTheProfileIntoTheStartItRuns() throws Exception {
        writeProfile("probe", """
                image: floci/floci:enforced
                port: 4599
                persistDir: /tmp/floci-persist-test
                services: s3,lambda
                """);

        List<String> args = parse(ProductProfile.AWS, "--profile", "probe")
                .buildStartCommand().dockerRunArgs(SOCKET);

        assertEquals(List.of(
                "-d", "--name", "floci",
                "-p", "4599:4566",
                "-v", "/sock:/sock",
                "-v", "/tmp/floci-persist-test:/app/data",
                "-e", "FLOCI_STORAGE_MODE=persistent",
                "-e", "FLOCI_SERVICES=s3,lambda",
                "floci/floci:enforced"), args);
    }

    @Test
    void withoutAProfileItStillUsesTheProductDefaults() {
        List<String> args = parse(ProductProfile.GCP).buildStartCommand().dockerRunArgs(SOCKET);

        assertEquals(List.of("-d", "--name", "floci-gcp", "-p", "4588:4588",
                "-v", "/sock:/sock", "floci/floci-gcp:latest"), args);
    }

    @Test
    void fieldsTheProfileOmitsKeepTheProductDefault() throws Exception {
        writeProfile("partial", "persistDir: /tmp/only-persist\n");

        List<String> args = parse(ProductProfile.OCI, "--profile", "partial")
                .buildStartCommand().dockerRunArgs(SOCKET);

        assertEquals(List.of("-d", "--name", "floci-oci", "-p", "4599:4599",
                "-v", "/sock:/sock",
                "-v", "/tmp/only-persist:/app/data",
                "-e", "FLOCI_OCI_STORAGE_MODE=persistent",
                "floci/floci-oci:latest"), args);
    }
}
