package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.commands.StartCommand;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.docker.DockerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine.ParseResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

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
                        "-e", "FLOCI_BASE_URL=http://localhost:4599",
                        "-e", "FLOCI_DOCKER_RESOURCE_NAMESPACE=probe",
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

    /** 'floci az setup' needs HTTPS (MSAL refuses an HTTP authority), so az always starts with TLS. */
    @Test
    void onlyAzStartsWithTlsEnabled() {
        assertEquals(List.of("-d", "--name", "floci-az", "-p", "4577:4577",
                        "-v", "/sock:/sock",
                        "-e", "FLOCI_AZ_TLS_ENABLED=true",
                        "floci/floci-az:latest"),
                parse("az", "start").dockerRunArgs(SOCKET));
        for (String tree : new String[]{"aws", "gcp", "oci"}) {
            assertFalse(parse(tree, "start").dockerRunArgs(SOCKET).stream().anyMatch(a -> a.contains("TLS")), tree);
        }
    }

    /**
     * Two instances of one emulator must not share a resource namespace, or each one's startup
     * sweep removes the other's Lambda/ECS/Cloud Run children. The default instance passes none,
     * so its child container names stay what they were.
     */
    @Test
    void theDefaultContainerPassesNoResourceNamespace() {
        for (String tree : new String[]{"aws", "gcp", "az", "oci"}) {
            assertFalse(parse(tree, "start").dockerRunArgs(SOCKET).stream()
                    .anyMatch(a -> a.contains("RESOURCE_NAMESPACE")), tree);
        }
    }

    @Test
    void aNonDefaultContainerNamesItsOwnNamespaceInEveryTree() {
        // The emulator prefixes child names with floci-<cloud>- itself, so the default-container
        // prefix is dropped: floci-gcp-b -> b, not floci-gcp-floci-gcp-b-...
        assertTrue(parse("start", "--container", "floci-b").dockerRunArgs(SOCKET)
                .contains("FLOCI_DOCKER_RESOURCE_NAMESPACE=b"));
        assertTrue(parse("gcp", "start", "--container", "floci-gcp-b").dockerRunArgs(SOCKET)
                .contains("FLOCI_GCP_DOCKER_RESOURCE_NAMESPACE=b"));
        assertTrue(parse("az", "start", "--container", "floci-az-b").dockerRunArgs(SOCKET)
                .contains("FLOCI_AZ_DOCKER_RESOURCE_NAMESPACE=b"));
        assertTrue(parse("oci", "start", "--container", "team-oci").dockerRunArgs(SOCKET)
                .contains("FLOCI_OCI_DOCKER_RESOURCE_NAMESPACE=team-oci"));
    }

    @Test
    void anExplicitNamespaceBeatsTheContainerName() {
        assertTrue(parse("start", "--container", "floci-b", "--namespace", "team-b")
                .dockerRunArgs(SOCKET).contains("FLOCI_DOCKER_RESOURCE_NAMESPACE=team-b"));
        assertTrue(parse("start", "--namespace", "team-a")
                .dockerRunArgs(SOCKET).contains("FLOCI_DOCKER_RESOURCE_NAMESPACE=team-a"));
    }

    @Test
    void theProfileNamespaceAppliesAndTheFlagStillWins() throws Exception {
        writeProfile("probe", "container: floci-probe\nnamespace: from-profile\n");
        assertTrue(parse("start", "--container", "floci-probe", "--namespace", "floci-probe")
                .dockerRunArgs(SOCKET).contains("FLOCI_DOCKER_RESOURCE_NAMESPACE=floci-probe"),
                "an explicit namespace is passed verbatim");

        assertTrue(parse("start", "--profile", "probe").dockerRunArgs(SOCKET)
                .contains("FLOCI_DOCKER_RESOURCE_NAMESPACE=from-profile"));
        assertTrue(parse("start", "--profile", "probe", "--namespace", "from-flag").dockerRunArgs(SOCKET)
                .contains("FLOCI_DOCKER_RESOURCE_NAMESPACE=from-flag"));
    }

    @Test
    void anInvalidNamespaceIsRejectedBeforeDockerIsTouched() {
        StartCommand start = parse("start", "--namespace", "bad/name");
        PrintStream err = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            assertEquals(2, start.call());
        } finally {
            System.setErr(err);
        }
    }

    /** Response URLs (SQS QueueUrl, OCI invoke endpoint) must name the port this instance owns. */
    @Test
    void aNonDefaultPortMovesTheBaseUrlWithIt() {
        assertTrue(parse("start", "--port", "14566").dockerRunArgs(SOCKET)
                .contains("FLOCI_BASE_URL=http://localhost:14566"));
        assertTrue(parse("oci", "start", "--port", "14599").dockerRunArgs(SOCKET)
                .contains("FLOCI_OCI_BASE_URL=http://localhost:14599"));
        for (String tree : new String[]{"aws", "gcp", "az", "oci"}) {
            assertFalse(parse(tree, "start").dockerRunArgs(SOCKET).stream()
                    .anyMatch(a -> a.contains("BASE_URL")), tree);
        }
    }

    /** Returned URLs must reach the emulator, so a remote endpoint's host is kept. */
    @Test
    void theBaseUrlKeepsTheEndpointsHost() {
        assertTrue(parse("start", "--endpoint", "http://floci.internal:4566", "--port", "14566")
                .dockerRunArgs(SOCKET).contains("FLOCI_BASE_URL=http://floci.internal:14566"));
    }

    /** BL-034: Docker reads "./data:/app/data" as a named volume, so the path goes in absolute. */
    @Test
    void aRelativePersistDirIsMountedAsAnAbsoluteBindPath() {
        String expected = Path.of("data").toAbsolutePath().normalize() + ":/app/data";

        List<String> args = parse("start", "--persist", "./data").dockerRunArgs(SOCKET);

        assertTrue(args.contains(expected), args.toString());
        assertFalse(args.contains("./data:/app/data"), args.toString());
    }

    /** BL-035: an out-of-range port or an unknown pull policy is refused before docker runs. */
    @Test
    void portAndPullPolicyAreValidated() {
        assertNull(parse("start").validationError());
        assertNull(parse("start", "--pull", "ALWAYS").validationError());
        assertTrue(parse("start", "--port", "0").validationError().contains("--port must be between 1 and 65535"));
        assertTrue(parse("start", "--port", "70000").validationError().contains("--port must be between 1 and 65535"));
        assertTrue(parse("start", "--pull", "alwayz").validationError().contains("Invalid --pull 'alwayz'"));
    }

    /** Pull policies are plain ASCII words: a Turkish default locale lowercases "MISSING" with a dotless i. */
    @Test
    void pullPolicyDoesNotDependOnTheDefaultLocale() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertNull(parse("start", "--pull", "MISSING").validationError());
        } finally {
            Locale.setDefault(before);
        }
    }

    private static final DockerClient.DockerHost LOCAL =
            new DockerClient.DockerHost(DockerClient.Kind.UNIX, "/var/run/docker.sock", null);
    private static final DockerClient.DockerHost REMOTE =
            new DockerClient.DockerHost(DockerClient.Kind.TCP, null, "tcp://build-host:2375");

    @Test
    void aLocalDaemonGetsItsPersistDirCreated() {
        Path state = tempDir.resolve("made").resolve("state");

        assertNull(parse("start", "--persist", state.toString()).preparePersistDir(LOCAL));

        assertTrue(Files.isDirectory(state));
    }

    /** A remote daemon reads the bind source on its own machine: nothing is created or refused here. */
    @Test
    void aRemoteDaemonsPersistDirIsNotCreatedLocally() throws Exception {
        Path blocker = Files.writeString(Files.createDirectories(tempDir).resolve("a-file"), "x");
        Path state = blocker.resolve("state"); // cannot exist locally: its parent is a file

        assertNull(parse("start", "--persist", state.toString()).preparePersistDir(REMOTE));
        assertFalse(Files.exists(state));
        assertNotNull(parse("start", "--persist", state.toString()).preparePersistDir(LOCAL));
    }

    /** A path that is not valid on this platform gets the same hint, not an exception from building the message. */
    @Test
    void anInvalidPersistPathIsReportedNotThrown() {
        String invalid = "data\u0000dir"; // NUL is rejected by every platform's Path

        String error = parse("start", "--persist", invalid).preparePersistDir(LOCAL);

        assertNotNull(error);
        assertTrue(error.startsWith("Invalid persist directory"), error);
        assertTrue(error.contains("Pass a --persist path"), error);
    }

    /**
     * A remote daemon's path is its own: an absolute one is passed on as given, never resolved by
     * this machine's rules (a Windows CLI would turn /srv/floci-data into a drive path).
     */
    @Test
    void aRemoteDaemonGetsThePersistPathExactlyAsGiven() {
        StartCommand start = parse("start", "--persist", "/srv/floci-data/../state");

        assertNull(start.preparePersistDir(REMOTE));
        assertEquals("/srv/floci-data/../state", start.bindSource(REMOTE));
        assertTrue(start.dockerRunArgs(SOCKET, REMOTE).contains("/srv/floci-data/../state:/app/data"));
    }

    /** Only a local daemon has relative paths resolved against this machine's working directory. */
    @Test
    void onlyALocalDaemonHasItsPersistPathResolved() {
        StartCommand start = parse("start", "--persist", "./data");
        String resolved = Path.of("data").toAbsolutePath().normalize().toString();

        assertEquals(resolved, start.bindSource(LOCAL));
        assertTrue(start.dockerRunArgs(SOCKET, LOCAL).contains(resolved + ":/app/data"));
    }

    /**
     * A remote daemon has no working directory of ours to resolve against, and docker reads a bare
     * name as a named volume: a relative path is refused before anything is started or stopped.
     */
    @ParameterizedTest
    @ValueSource(strings = {"data", "./data", "../state", "C:data"})
    void aRemoteDaemonRefusesARelativePersistPath(String relative) {
        String error = parse("start", "--persist", relative).preparePersistDir(REMOTE);

        assertNotNull(error);
        assertTrue(error.contains("must be an absolute path on the daemon's machine"), error);
        assertTrue(error.contains("'" + relative + "'"), error);
    }

    /** Absolute for the daemon's system, whichever it is; this machine's path rules do not decide. */
    @ParameterizedTest
    @ValueSource(strings = {"/srv/floci-data", "C:\\floci\\data", "c:/floci/data", "\\\\nas\\share\\floci"})
    void aRemoteDaemonAcceptsAnAbsolutePathOfEitherSystem(String absolute) {
        StartCommand start = parse("start", "--persist", absolute);

        assertNull(start.preparePersistDir(REMOTE));
        assertEquals(absolute, start.bindSource(REMOTE));
    }

    /**
     * What preparePersistDir accepts, dockerRunArgs can build. Restart runs the check before the
     * stop, so a path this machine cannot even parse must not throw afterwards for a remote daemon.
     */
    @Test
    void aPathThisMachineCannotParseStillBuildsForARemoteDaemon() {
        StartCommand start = parse("start", "--persist", "/srv/data\u0000dir");

        assertNull(start.preparePersistDir(REMOTE));
        assertDoesNotThrow(() -> start.dockerRunArgs(SOCKET, REMOTE));
        assertNotNull(start.preparePersistDir(LOCAL));
    }

    /**
     * An existing directory this user cannot write to is left to the emulator, which runs as its
     * own user and reports an unwritable data directory itself.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anExistingDirectoryIsAcceptedWithoutAWriteCheck() throws Exception {
        Path readOnly = Files.createDirectories(tempDir.resolve("read-only"));
        assertTrue(readOnly.toFile().setWritable(false));
        try {
            assertNull(parse("start", "--persist", readOnly.toString()).preparePersistDir(LOCAL));
        } finally {
            readOnly.toFile().setWritable(true);
        }
    }

    @Test
    void readinessPollKeepsTheHostFromTheEndpointWhenSwappingThePort() {
        assertEquals("http://floci.internal:4599", StartCommand.withPort("http://floci.internal:4566", 4599));
        assertEquals("http://localhost:4599", StartCommand.withPort("http://localhost:4566", 4599));
        assertEquals("http://localhost:4599", StartCommand.withPort("not a url", 4599));
        // BL-015: only the port changes.
        assertEquals("http://user:pw@floci.internal:4599/base?x=1#f",
                StartCommand.withPort("http://user:pw@floci.internal:4566/base?x=1#f", 4599));
        // Escaped characters stay escaped: %26 must not turn into a second query parameter.
        assertEquals("http://floci.internal:4599/b?token=a%26b",
                StartCommand.withPort("http://floci.internal:4566/b?token=a%26b", 4599));
        assertEquals("http://[::1]:4599", StartCommand.withPort("http://[::1]:4566", 4599));
    }
}
