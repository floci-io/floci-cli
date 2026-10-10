package io.floci.cli.unit;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StartCommand;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerClient.DockerHost;
import io.floci.cli.docker.DockerClient.Kind;
import io.floci.cli.docker.DockerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** BL-058: start reports a busy host port itself, before pulling or running anything. */
class StartPreflightTest {

    private static final DockerHost LOCAL = new DockerHost(Kind.UNIX, "/var/run/docker.sock", null);
    private static final DockerHost REMOTE = new DockerHost(Kind.TCP, null, "tcp://build-host:2375");

    @TempDir
    Path tempDir;

    /** A docker that has no container, records what start asked of it, and is reached through {@code daemon}. */
    private static class FakeDocker extends DockerClient {
        final DockerHost daemon;
        final List<String> calls = new ArrayList<>();
        final List<String> runArgs = new ArrayList<>();

        FakeDocker(DockerHost daemon) {
            this.daemon = daemon;
        }

        @Override
        public DockerHost daemonHost() {
            return daemon;
        }

        @Override
        public Optional<ContainerInfo> inspectContainer(String name) throws DockerException {
            return Optional.empty();
        }

        @Override
        public void pull(String image, String policy) {
            calls.add("pull");
        }

        @Override
        public String startContainer(List<String> args) {
            runArgs.addAll(args);
            calls.add("run");
            return "0123456789abcdef";
        }
    }

    private record Out(int exit, String err) {}

    private static Out start(DockerClient docker, String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        System.setErr(new PrintStream(errBuf));
        try {
            int exit = new CommandLine(new StartCommand(ProductProfile.GCP, docker)).execute(args);
            return new Out(exit, errBuf.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    @Test
    void aBusyPortStopsTheStartWithAHint() throws Exception {
        FakeDocker docker = new FakeDocker(LOCAL);
        try (ServerSocket taken = new ServerSocket()) {
            taken.bind(new InetSocketAddress(0));
            int port = taken.getLocalPort();

            Out r = start(docker, "--port", String.valueOf(port), "--container", "floci-gcp-preflight", "--detach");

            assertEquals(1, r.exit());
            assertTrue(r.err().contains("Port " + port + " is already in use"), r.err());
            assertTrue(r.err().contains("--port <other>"), r.err());
            assertEquals(List.of(), docker.calls, "nothing is pulled or run for a start that cannot bind");
        }
    }

    @Test
    void aFreePortGoesOnToPullAndRun() throws Exception {
        FakeDocker docker = new FakeDocker(LOCAL);
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }

        Out r = start(docker, "--port", String.valueOf(port), "--container", "floci-gcp-preflight", "--detach");

        assertEquals(0, r.exit(), r.err());
        assertEquals(List.of("pull", "run"), docker.calls);
    }

    /** The port is published on the daemon's machine, so what holds it here is beside the point. */
    @Test
    void aBusyLocalPortDoesNotBlockARemoteDaemon() throws Exception {
        FakeDocker docker = new FakeDocker(REMOTE);
        try (ServerSocket taken = new ServerSocket()) {
            taken.bind(new InetSocketAddress(0));

            Out r = start(docker, "--port", String.valueOf(taken.getLocalPort()),
                    "--container", "floci-gcp-preflight", "--detach");

            assertEquals(0, r.exit(), r.err());
            assertEquals(List.of("pull", "run"), docker.calls);
        }
    }

    /** A daemon reached over SSH (DOCKER_HOST or a context) is remote too: its ports are not this machine's. */
    @Test
    void aDaemonReachedOverSshIsNotProbedLocally() throws Exception {
        DockerHost ssh = DockerClient.parseDockerHost("ssh://deploy@build-host", null, "Linux");
        assertEquals(Kind.TCP, ssh.kind());
        assertEquals("ssh://deploy@build-host", ssh.raw());
        assertFalse(StartCommand.probesPortLocally(ssh, 4588));

        FakeDocker docker = new FakeDocker(ssh);
        try (ServerSocket taken = new ServerSocket()) {
            taken.bind(new InetSocketAddress(0));

            Out r = start(docker, "--port", String.valueOf(taken.getLocalPort()),
                    "--container", "floci-gcp-preflight", "--detach");

            assertEquals(0, r.exit(), r.err());
            assertEquals(List.of("pull", "run"), docker.calls);
        }
    }

    /**
     * One answer for the whole start: a daemon that is remote only through its context also gets
     * its persist path passed on as given, and a relative one refused, as with DOCKER_HOST=tcp://.
     */
    @Test
    void aRemoteContextDecidesThePersistPathToo() {
        FakeDocker docker = new FakeDocker(REMOTE);

        Out ok = start(docker, "--persist", "/srv/floci-data", "--container", "floci-gcp-preflight", "--detach");

        assertEquals(0, ok.exit(), ok.err());
        assertTrue(docker.runArgs.contains("/srv/floci-data:/app/data"), docker.runArgs.toString());

        FakeDocker refused = new FakeDocker(REMOTE);
        Out relative = start(refused, "--persist", "data", "--container", "floci-gcp-preflight", "--detach");

        assertEquals(1, relative.exit());
        assertTrue(relative.err().contains("must be an absolute path on the daemon's machine"), relative.err());
        assertEquals(List.of(), refused.calls);
    }

    @Test
    void onlyARealPortOnALocalDaemonIsProbed() {
        assertTrue(StartCommand.probesPortLocally(LOCAL, 4588));
        assertTrue(StartCommand.probesPortLocally(new DockerHost(Kind.NPIPE, "\\\\.\\pipe\\docker_engine", null), 4588));
        assertFalse(StartCommand.probesPortLocally(REMOTE, 4588));
        // Not ports: a socket cannot even be opened on them, and docker reports them itself.
        assertFalse(StartCommand.probesPortLocally(LOCAL, 70000));
        assertFalse(StartCommand.probesPortLocally(LOCAL, 0));
        assertFalse(StartCommand.probesPortLocally(LOCAL, -1));
    }

    /** A number that is no port must reach docker's own error, not a stack trace from the probe. */
    @Test
    void anOutOfRangePortIsNotProbed() {
        FakeDocker docker = new FakeDocker(LOCAL);

        Out r = start(docker, "--port", "70000", "--container", "floci-gcp-preflight", "--detach");

        assertFalse(r.err().contains("Exception"), r.err());
        assertFalse(r.err().contains("already in use"), r.err());
    }

    @Test
    void aMissingSocketSaysHowToBringDockerUp() {
        DockerHost missing = new DockerHost(Kind.UNIX, tempDir.resolve("docker.sock").toString(), null);

        String guidance = StartCommand.socketGuidance(missing);

        assertTrue(guidance.contains("docker.sock not found"), guidance);
        assertTrue(guidance.contains("Docker"), guidance);
        assertTrue(guidance.endsWith(", then re-run the command."), guidance);
        // The socket does not exist, so permissions on it are not the fix.
        assertFalse(guidance.contains("chmod"), guidance);
        assertFalse(guidance.contains("docker group"), guidance);
    }

    @Test
    void aSocketThatExistsOrARemoteDaemonGetsNoSocketGuidance() throws Exception {
        Path present = Files.createFile(tempDir.resolve("docker.sock"));

        assertEquals("", StartCommand.socketGuidance(new DockerHost(Kind.UNIX, present.toString(), null)));
        assertEquals("", StartCommand.socketGuidance(REMOTE));
    }

    /** The inspect failure carries the guidance for the daemon docker resolved. */
    @Test
    void anUnreachableDaemonWithAMissingSocketGetsTheGuidance() {
        DockerHost missing = new DockerHost(Kind.UNIX, tempDir.resolve("docker.sock").toString(), null);
        FakeDocker docker = new FakeDocker(missing) {
            @Override
            public Optional<ContainerInfo> inspectContainer(String name) throws DockerException {
                throw new DockerException("Cannot connect to the Docker daemon");
            }
        };

        Out r = start(docker, "--container", "floci-gcp-preflight", "--detach");

        assertEquals(1, r.exit());
        assertTrue(r.err().contains("Cannot connect to the Docker daemon"), r.err());
        assertTrue(r.err().contains("docker.sock not found"), r.err());
        assertEquals(List.of(), docker.calls);
    }

    /** A context selected with DOCKER_CONTEXT or 'docker context use' is not in the environment: docker is asked. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theDaemonComesFromDockersActiveContext() throws Exception {
        assumeTrue(DockerClient.dockerHost().raw() == null, "DOCKER_HOST or DOCKER_SOCK is set and wins");
        Path argsFile = tempDir.resolve("args");
        Path remoteContext = tempDir.resolve("docker-remote");
        Files.writeString(remoteContext, "#!/bin/sh\necho \"$1 $2\" > '" + argsFile + "'\necho ssh://deploy@build-host\n");
        Path noContexts = tempDir.resolve("docker-plain");
        Files.writeString(noContexts, "#!/bin/sh\necho unknown command >&2\nexit 125\n");
        assertTrue(remoteContext.toFile().setExecutable(true));
        assertTrue(noContexts.toFile().setExecutable(true));

        DockerHost fromContext = new DockerClient(remoteContext.toString()).daemonHost();

        assertEquals(Kind.TCP, fromContext.kind());
        assertEquals("ssh://deploy@build-host", fromContext.raw());
        assertEquals("context inspect", Files.readString(argsFile).trim());
        // No contexts (Podman, an old docker): the environment's answer stands.
        assertEquals(DockerClient.dockerHost(), new DockerClient(noContexts.toString()).daemonHost());
    }
}
