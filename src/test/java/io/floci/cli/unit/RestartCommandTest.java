package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.ProductProfile;
import io.floci.cli.commands.RestartCommand;
import io.floci.cli.commands.StartCommand;
import io.floci.cli.commands.StopCommand;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
    void withoutAProfileItStillUsesTheProductDefaults() throws Exception {
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
     * A persist directory that cannot be created fails the restart before the stop, so the running
     * instance is left alone instead of being removed and never started again.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anUnwritablePersistDirFailsBeforeTheStop() throws Exception {
        Path readOnly = Files.createDirectories(tempDir.resolve("read-only"));
        assertTrue(readOnly.toFile().setWritable(false));
        writeProfile("ro", "container: floci-ro-none\npersistDir: " + readOnly.resolve("state") + "\n");
        RestartCommand restart = parse(ProductProfile.AWS, "--profile", "ro");

        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        try {
            // Root writes through the permission bits; the restart would then reach Docker.
            assumeFalse(Files.isWritable(readOnly), "permissions do not bind this user (root)");
            System.setOut(new PrintStream(outBuf));
            System.setErr(new PrintStream(errBuf));
            assertEquals(1, restart.call());
        } finally {
            System.setOut(out);
            System.setErr(err);
            readOnly.toFile().setWritable(true);
        }
        assertTrue(errBuf.toString().contains("Could not create the persist directory"), errBuf.toString());
        assertFalse(outBuf.toString().contains("Stopping"), "nothing was stopped: " + outBuf);
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
    void theStopItRunsRemovesTheContainer() throws Exception {
        var stop = parse(ProductProfile.AWS).buildStopCommand();

        assertEquals(Boolean.TRUE, new CommandLine(stop).getCommandSpec().findOption("--remove").getValue());
    }

    /** BL-033: without a profile, the running container is the record of how it was started. */
    @Test
    void withoutAProfileTheRunningContainersSettingsCarryOver() throws Exception {
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

    /** What a restart did, in order: "stop" and "start" as it ran them. */
    private RestartCommand recordingRestart(DockerClient docker, List<String> ran) {
        RestartCommand restart = new RestartCommand(ProductProfile.AWS, docker) {
            @Override
            public StartCommand buildStartCommand() throws DockerException {
                super.buildStartCommand(); // the real one first, so its docker failure still surfaces
                return new StartCommand(ProductProfile.AWS) {
                    @Override
                    public Integer call() {
                        ran.add("start");
                        return 0;
                    }
                };
            }

            @Override
            public StopCommand buildStopCommand() {
                return new StopCommand(ProductProfile.AWS) {
                    @Override
                    public Integer call() {
                        ran.add("stop");
                        return 0;
                    }
                };
            }
        };
        new CommandLine(restart)
                .setDefaultValueProvider(new ProfileDefaultValueProvider(new ProfileStore(tempDir)))
                .parseArgs();
        return restart;
    }

    private record Ran(int exit, String out, String err) {}

    private static Ran run(RestartCommand restart) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            return new Ran(restart.call(), outBuf.toString(), errBuf.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    /** BL-033: with no container there is nothing to stop, so restart only starts. */
    @Test
    void aMissingContainerIsStartedWithoutAStop() {
        DockerClient absent = new DockerClient() {
            @Override
            public Optional<RunSettings> inspectRunSettings(String name, int containerPort) {
                return Optional.empty();
            }

            @Override
            public Optional<ContainerInfo> inspectContainer(String name) {
                return Optional.empty();
            }
        };
        List<String> ran = new ArrayList<>();

        Ran r = run(recordingRestart(absent, ran));

        assertEquals(0, r.exit());
        assertEquals(List.of("start"), ran);
        assertTrue(r.out().contains("not found; starting it"), r.out());
    }

    @Test
    void anExistingContainerIsStoppedThenStarted() {
        DockerClient present = new DockerClient() {
            @Override
            public Optional<RunSettings> inspectRunSettings(String name, int containerPort) {
                return Optional.of(new RunSettings("floci/floci:0.9", 14566, null, Map.of()));
            }

            @Override
            public Optional<ContainerInfo> inspectContainer(String name) {
                return Optional.of(new ContainerInfo("abc", name, "floci/floci:0.9", "running", ""));
            }
        };
        List<String> ran = new ArrayList<>();

        Ran r = run(recordingRestart(present, ran));

        assertEquals(0, r.exit());
        assertEquals(List.of("stop", "start"), ran);
    }

    /**
     * A failed read of the running container's settings is not "no container": restart must stop
     * there, or it would remove the instance and bring it back on the default port without its data.
     */
    @Test
    void settingsThatCannotBeReadFailTheRestartBeforeTheStop() {
        DockerClient stalled = new DockerClient() {
            @Override
            public Optional<RunSettings> inspectRunSettings(String name, int containerPort) throws DockerException {
                throw new DockerException("'docker container inspect' did not finish within 30s.");
            }

            @Override
            public Optional<ContainerInfo> inspectContainer(String name) {
                return Optional.of(new ContainerInfo("abc", name, "floci/floci:0.9", "running", ""));
            }
        };
        List<String> ran = new ArrayList<>();

        Ran r = run(recordingRestart(stalled, ran));

        assertEquals(1, r.exit());
        assertEquals(List.of(), ran);
        assertTrue(r.err().contains("did not finish within 30s"), r.err());
        assertTrue(r.err().contains("floci restart"), r.err());
    }
}
