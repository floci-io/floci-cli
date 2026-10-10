package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.ProductProfile;
import io.floci.cli.commands.RestartCommand;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.docker.DockerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    // Through the real wiring, so restart sees the provider the parse used.
    /** A restart whose docker lookups answer from {@code settings} (none: no such container). */
    private RestartCommand restartWith(ProductProfile product, DockerClient.RunSettings settings, String... args) {
        DockerClient docker = new DockerClient() {
            @Override
            public Optional<RunSettings> inspectRunSettings(String name, int containerPort) {
                return Optional.ofNullable(settings);
            }
        };
        RestartCommand restart = new RestartCommand(product, docker);
        new CommandLine(restart)
                .setDefaultValueProvider(new ProfileDefaultValueProvider(new ProfileStore(tempDir)))
                .parseArgs(args);
        return restart;
    }

    private RestartCommand parse(ProductProfile product, String... args) {
        List<String> all = new ArrayList<>();
        if (product != ProductProfile.AWS) all.add(product.name());
        all.add("restart");
        all.addAll(List.of(args));
        ParseResult r = FlociCli.buildCommandLine(new ProfileStore(tempDir)).parseArgs(all.toArray(String[]::new));
        while (r.hasSubcommand()) r = r.subcommand();
        return (RestartCommand) r.commandSpec().userObject();
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
                "-e", "FLOCI_BASE_URL=http://localhost:4599",
                "floci/floci:enforced"), args);
    }

    /**
     * Values reach a real 'start' through picocli, which interpolates ${env:...}. Restart applies
     * the profile itself, so it has to go through the same machinery or one profile means two
     * different host directories depending on which command you ran.
     */
    @Test
    void interpolatesProfileValuesExactlyAsStartDoes() throws Exception {
        writeProfile("interp", "persistDir: ${env:HOME}/floci-data\n");

        List<String> args = parse(ProductProfile.AWS, "--profile", "interp")
                .buildStartCommand().dockerRunArgs(SOCKET);

        assertTrue(args.contains(System.getenv("HOME") + "/floci-data:/app/data"),
                "restart must expand the profile the way start does, but got: " + args);
        assertFalse(args.stream().anyMatch(a -> a.contains("${env:")), args.toString());
    }

    @Test
    void carriesTheProfileNamespaceIntoTheStartItRuns() throws Exception {
        writeProfile("ns", "container: floci-oci-b\nnamespace: team-b\n");

        List<String> args = parse(ProductProfile.OCI, "--profile", "ns")
                .buildStartCommand().dockerRunArgs(SOCKET);

        assertTrue(args.contains("FLOCI_OCI_DOCKER_RESOURCE_NAMESPACE=team-b"), args.toString());
    }

    @Test
    void withoutAProfileItStillUsesTheProductDefaults() {
        List<String> args = restartWith(ProductProfile.GCP, null).buildStartCommand().dockerRunArgs(SOCKET);

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

    /** A namespace start would reject must fail restart before it stops anything. */
    @Test
    void anInvalidProfileNamespaceFailsBeforeTheStop() throws Exception {
        writeProfile("bad", "container: floci-bad\nnamespace: bad/name\n");
        RestartCommand restart = parse(ProductProfile.AWS, "--profile", "bad");

        PrintStream err = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            assertEquals(2, restart.call());
        } finally {
            System.setErr(err);
        }
    }

    /**
     * One snapshot: restart applies the profile the parse read, not a second read of the file, so
     * an edit or delete in between cannot mix two versions into one restart.
     */
    @Test
    void usesTheParsesSnapshotEvenIfTheFileChangesAfterwards() throws Exception {
        writeProfile("snap", "container: floci-snap\npersistDir: /from-the-parse\n");
        RestartCommand restart = parse(ProductProfile.AWS, "--profile", "snap");

        writeProfile("snap", "container: floci-snap\npersistDir: /edited-later\n");
        List<String> args = restart.buildStartCommand().dockerRunArgs(SOCKET);
        assertTrue(args.contains("/from-the-parse:/app/data"), args.toString());

        Files.delete(tempDir.resolve("snap.yaml"));
        assertTrue(restart.buildStartCommand().dockerRunArgs(SOCKET).contains("/from-the-parse:/app/data"));
    }

    /** BL-029: the stop removes the container, so start needs no pause and no second removal. */
    @Test
    void theStopItRunsRemovesTheContainer() {
        var stop = parse(ProductProfile.AWS).buildStopCommand();

        assertEquals(Boolean.TRUE, new CommandLine(stop).getCommandSpec().findOption("--remove").getValue());
    }

    /** BL-033: without a profile, the running container is the record of how it was started. */
    @Test
    void withoutAProfileTheRunningContainersSettingsCarryOver() {
        var running = new DockerClient.RunSettings("floci/floci-gcp:0.9", 14588, "/data/gcp",
                Map.of("FLOCI_GCP_SERVICES", "storage", "FLOCI_GCP_DOCKER_RESOURCE_NAMESPACE", "team"));

        List<String> args = restartWith(ProductProfile.GCP, running).buildStartCommand().dockerRunArgs(SOCKET);

        assertTrue(args.contains("14588:4588"), args.toString());
        assertTrue(args.contains("/data/gcp:/app/data"), args.toString());
        assertTrue(args.contains("FLOCI_GCP_SERVICES=storage"), args.toString());
        assertTrue(args.contains("FLOCI_GCP_DOCKER_RESOURCE_NAMESPACE=team"), args.toString());
        assertEquals("floci/floci-gcp:0.9", args.get(args.size() - 1));
    }

    /** A profile still decides: carry-over only fills in for a restart without one. */
    @Test
    void aProfileWinsOverTheRunningContainersSettings() throws Exception {
        writeProfile("pinned", "port: 15000\n");
        var running = new DockerClient.RunSettings("floci/floci:0.9", 14566, "/data/x", Map.of());

        List<String> args = restartWith(ProductProfile.AWS, running, "--profile", "pinned")
                .buildStartCommand().dockerRunArgs(SOCKET);

        assertTrue(args.contains("15000:4566"), args.toString());
        assertFalse(args.contains("/data/x:/app/data"), args.toString());
    }
}
