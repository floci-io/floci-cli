package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.commands.StartCommand;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine.ParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The issue #22 regression: a profile's persistDir must produce the bind mount and the
 * persistent storage mode, or every start silently drops state into a fresh anonymous volume.
 * Built without touching Docker — the host socket arguments are passed in.
 */
class StartCommandArgsTest {

    private static final List<String> SOCKET = List.of("-v", "/sock:/sock");

    @TempDir
    Path tempDir;

    private StartCommand parse(String... args) {
        ParseResult r = FlociCli.buildCommandLine(new ProfileStore(tempDir)).parseArgs(args);
        while (r.hasSubcommand()) r = r.subcommand();
        return (StartCommand) r.commandSpec().userObject();
    }

    private void writeProfile(String name, String yaml) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve(name + ".yaml"), yaml);
    }

    @Test
    void profilePersistDirProducesTheBindMountAndPersistentStorageMode() throws Exception {
        writeProfile("probe", """
                container: floci-probe
                image: floci/floci:enforced
                port: 4599
                persistDir: /tmp/floci-persist-test
                services: s3,lambda
                """);

        assertEquals(List.of(
                        "-d", "--name", "floci-probe",
                        "-p", "4599:4566",
                        "-v", "/sock:/sock",
                        "-v", "/tmp/floci-persist-test:/app/data",
                        "-e", "FLOCI_STORAGE_MODE=persistent",
                        "-e", "FLOCI_SERVICES=s3,lambda",
                        "floci/floci:enforced"),
                parse("start", "--profile", "probe").dockerRunArgs(SOCKET));
    }

    @Test
    void perProductEnvPrefixesComeFromTheProductNotTheProfile() throws Exception {
        writeProfile("probe", """
                persistDir: /tmp/floci-persist-test
                services: storage
                """);

        assertEquals(List.of(
                        "-d", "--name", "floci-gcp",
                        "-p", "4588:4588",
                        "-v", "/sock:/sock",
                        "-v", "/tmp/floci-persist-test:/app/data",
                        "-e", "FLOCI_GCP_STORAGE_MODE=persistent",
                        "-e", "FLOCI_GCP_SERVICES=storage",
                        "floci/floci-gcp:latest"),
                parse("gcp", "start", "--profile", "probe").dockerRunArgs(SOCKET));
    }

    @Test
    void noPersistDirMeansNoMountAndNoStorageMode() {
        List<String> args = parse("start").dockerRunArgs(SOCKET);

        assertEquals(List.of("-d", "--name", "floci", "-p", "4566:4566",
                "-v", "/sock:/sock", "floci/floci:latest"), args);
        assertFalse(args.contains("/app/data"));
        assertFalse(args.stream().anyMatch(a -> a.contains("STORAGE_MODE")));
    }

    @Test
    void explicitPersistFlagStillWinsOverTheProfile() throws Exception {
        writeProfile("probe", "persistDir: /from-profile\n");

        assertTrue(parse("start", "--profile", "probe", "--persist", "/from-flag")
                .dockerRunArgs(SOCKET).contains("/from-flag:/app/data"));
    }

    @Test
    void readinessPollKeepsTheHostFromTheEndpointWhenSwappingThePort() {
        assertEquals("http://floci.internal:4599", StartCommand.withPort("http://floci.internal:4566", 4599));
        assertEquals("http://localhost:4599", StartCommand.withPort("http://localhost:4566", 4599));
        assertEquals("http://localhost:4599", StartCommand.withPort("not a url", 4599));
    }
}
