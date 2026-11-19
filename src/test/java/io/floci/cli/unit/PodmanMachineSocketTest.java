package io.floci.cli.unit;

import io.floci.cli.commands.StartCommand;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerClient.DockerHost;
import io.floci.cli.docker.DockerClient.Kind;
import io.floci.cli.docker.DockerException;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #78: with {@code podman machine} on macOS the socket the docker CLI reaches is a forwarder on
 * the Mac, so {@code start} mounts the daemon's own socket inside the VM instead.
 */
class PodmanMachineSocketTest {

    private static final String MAC_SOCKET = "/var/folders/xx/T/podman/podman-machine-default-api.sock";
    private static final DockerHost MAC_PODMAN = new DockerHost(Kind.UNIX, MAC_SOCKET, "unix://" + MAC_SOCKET);

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "UNIX, /var/run/docker.sock, -, Mac OS X, true",
            "UNIX, /var/run/docker.sock, '  ', Mac OS X, true",
            // DOCKER_SOCK names the socket to mount, so it wins over asking the daemon.
            "UNIX, /var/run/docker.sock, /run/podman/podman.sock, Mac OS X, false",
            // No VM in between on Linux: the local path is the daemon's.
            "UNIX, /run/user/1000/podman/podman.sock, -, Linux, false",
            "NPIPE, //./pipe/podman, -, Windows 11, false",
            "TCP, -, -, Mac OS X, false"})
    void onlyALocalSocketOnMacIsAsked(Kind kind, String socketPath, String dockerSock, String os, boolean expected) {
        assertEquals(expected, DockerClient.podmanMachineMayApply(new DockerHost(kind, socketPath, null), dockerSock, os));
    }

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "unix:///run/podman/podman.sock, /run/podman/podman.sock",
            "/run/user/501/podman/podman.sock, /run/user/501/podman/podman.sock",
            "'  unix:///run/podman/podman.sock\n', /run/podman/podman.sock",
            "'', -",
            "-, -",
            // Already the path in use: nothing to change.
            "unix://" + MAC_SOCKET + ", -"})
    void theReportedSocketBecomesTheBindSource(String reported, String expected) {
        assertEquals(Optional.ofNullable(expected), DockerClient.remoteSocketMountSource(reported, MAC_SOCKET));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aPodmanDaemonIsAskedWhereItsSocketIs() throws Exception {
        Path podmanArgs = tempDir.resolve("podman-args");
        DockerClient docker = client(
                "echo 'Podman Engine|Conmon|OCI Runtime (crun)|Engine|'",
                "echo \"$@\" > '" + podmanArgs + "'\necho unix:///run/user/501/podman/podman.sock");

        assertEquals(Optional.of("/run/user/501/podman/podman.sock"),
                docker.podmanMachineSocket(MAC_PODMAN, null, "Mac OS X"));
        assertEquals("--url unix://" + MAC_SOCKET + " info --format {{.Host.RemoteSocket.Path}}",
                Files.readString(podmanArgs).trim());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void dockerDesktopIsLeftAlone() throws Exception {
        DockerClient docker = client("echo 'Engine|containerd|runc|docker-init|'", "echo podman must not run >&2\nexit 1");

        assertEquals(Optional.empty(), docker.podmanMachineSocket(MAC_PODMAN, null, "Mac OS X"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anUnreachableDaemonIsNotAnsweredHere() throws Exception {
        DockerClient docker = client("echo 'Cannot connect' >&2\nexit 1", "exit 1");

        assertEquals(Optional.empty(), docker.podmanMachineSocket(MAC_PODMAN, null, "Mac OS X"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void podmanThatCannotAnswerIsAnError() throws Exception {
        DockerClient docker = client("echo 'Podman Engine|'", "echo 'Error: unable to connect' >&2\nexit 125");

        DockerException e = assertThrows(DockerException.class,
                () -> docker.podmanMachineSocket(MAC_PODMAN, null, "Mac OS X"));
        assertTrue(e.getMessage().contains("unable to connect"), e.getMessage());
    }

    @Test
    void startMountsTheVmSocket() {
        List<String> args = StartCommand.socketRunArgs(answering(Optional.of("/run/podman/podman.sock")), MAC_PODMAN, printer(new ByteArrayOutputStream()));

        assertEquals(List.of("-v", "/run/podman/podman.sock:/var/run/docker.sock"), args);
    }

    @Test
    void startWithoutAVmSocketKeepsTheEnvironmentsMount() {
        List<String> args = StartCommand.socketRunArgs(answering(Optional.empty()), MAC_PODMAN, printer(new ByteArrayOutputStream()));

        assertEquals(DockerClient.dockerSocketRunArgs(), args);
    }

    @Test
    void startWarnsWithANextStepWhenPodmanCannotAnswer() {
        DockerClient failing = new DockerClient() {
            @Override
            public Optional<String> podmanMachineSocket(DockerHost daemon) throws DockerException {
                throw new DockerException("podman binary not found in PATH. Install Podman or add it to PATH.");
            }
        };
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        List<String> args = StartCommand.socketRunArgs(failing, MAC_PODMAN, printer(err));

        assertEquals(DockerClient.dockerSocketRunArgs(), args);
        String warning = err.toString();
        assertTrue(warning.contains("podman binary not found"), warning);
        assertTrue(warning.contains("set DOCKER_SOCK"), warning);
        assertTrue(warning.contains("re-run the command"), warning);
    }

    private DockerClient client(String dockerScript, String podmanScript) throws Exception {
        Path docker = script("docker", dockerScript);
        Path podman = script("podman", podmanScript);
        return new DockerClient(docker.toString(), podman.toString(), Duration.ofSeconds(10));
    }

    private Path script(String name, String body) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    private static DockerClient answering(Optional<String> socket) {
        return new DockerClient() {
            @Override
            public Optional<String> podmanMachineSocket(DockerHost daemon) {
                return socket;
            }
        };
    }

    private static Printer printer(ByteArrayOutputStream err) {
        return new Printer(new PrintStream(new ByteArrayOutputStream()), new PrintStream(err), OutputFormat.text, false);
    }
}
