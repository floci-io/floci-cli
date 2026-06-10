package io.floci.cli.unit;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerClient.DockerHost;
import io.floci.cli.docker.DockerClient.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DockerHostResolveTest {

    @Test
    void rootlessPodmanUnixSocket() {
        DockerHost h = DockerClient.parseDockerHost(
                "unix:///run/user/1000/podman/podman.sock", null, "Linux");
        assertEquals(Kind.UNIX, h.kind());
        assertEquals("/run/user/1000/podman/podman.sock", h.socketPath());
    }

    @Test
    void rootfulDockerUnixSocket() {
        DockerHost h = DockerClient.parseDockerHost("unix:///var/run/docker.sock", null, "Linux");
        assertEquals(Kind.UNIX, h.kind());
        assertEquals("/var/run/docker.sock", h.socketPath());
    }

    @Test
    void remoteTcpDaemon() {
        DockerHost h = DockerClient.parseDockerHost("tcp://10.0.0.1:2375", null, "Linux");
        assertEquals(Kind.TCP, h.kind());
        assertNull(h.socketPath());
        assertEquals("tcp://10.0.0.1:2375", h.raw());
    }

    @Test
    void barePathTreatedAsUnixSocket() {
        DockerHost h = DockerClient.parseDockerHost("/run/user/1000/podman/podman.sock", null, "Linux");
        assertEquals(Kind.UNIX, h.kind());
        assertEquals("/run/user/1000/podman/podman.sock", h.socketPath());
    }

    @Test
    void npipeScheme() {
        DockerHost h = DockerClient.parseDockerHost("npipe:////./pipe/podman", null, "Windows 11");
        assertEquals(Kind.NPIPE, h.kind());
        assertEquals("//./pipe/podman", h.socketPath());
    }

    @Test
    void dockerHostTakesPrecedenceOverDockerSock() {
        DockerHost h = DockerClient.parseDockerHost(
                "unix:///run/podman/podman.sock", "/var/run/docker.sock", "Linux");
        assertEquals(Kind.UNIX, h.kind());
        assertEquals("/run/podman/podman.sock", h.socketPath());
    }

    @Test
    void fallsBackToDockerSock() {
        DockerHost h = DockerClient.parseDockerHost(null, "/custom/docker.sock", "Linux");
        assertEquals(Kind.UNIX, h.kind());
        assertEquals("/custom/docker.sock", h.socketPath());
    }

    @Test
    void blankEnvIgnored() {
        DockerHost h = DockerClient.parseDockerHost("  ", "  ", "Linux");
        assertEquals(Kind.UNIX, h.kind());
        assertEquals("/var/run/docker.sock", h.socketPath());
    }

    @Test
    void defaultLinuxSocket() {
        DockerHost h = DockerClient.parseDockerHost(null, null, "Linux");
        assertEquals(Kind.UNIX, h.kind());
        assertEquals("/var/run/docker.sock", h.socketPath());
    }

    @Test
    void defaultWindowsPipe() {
        DockerHost h = DockerClient.parseDockerHost(null, null, "Windows 11");
        assertEquals(Kind.NPIPE, h.kind());
        assertEquals("\\\\.\\pipe\\docker_engine", h.socketPath());
    }
}
