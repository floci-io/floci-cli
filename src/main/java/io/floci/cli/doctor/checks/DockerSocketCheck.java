package io.floci.cli.doctor.checks;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;

import java.nio.file.Files;
import java.nio.file.Path;

public class DockerSocketCheck implements Check {

    @Override
    public String name() {
        return "docker.socket";
    }

    @Override
    public CheckResult run(String endpoint, String container) {
        return check(DockerClient.dockerHost());
    }

    /** The check for a daemon reached through {@code host}; pure, so start can ask about the daemon it resolved. */
    public static CheckResult check(DockerClient.DockerHost host) {
        switch (host.kind()) {
            case NPIPE:
                return CheckResult.ok("docker.socket", "Windows named pipe (" + host.socketPath() + ")");
            case TCP:
                return CheckResult.ok("docker.socket", "remote daemon (" + host.raw() + ")");
            default:
                break;
        }
        String socketPath = host.socketPath();
        if (Files.exists(Path.of(socketPath))) {
            return CheckResult.ok("docker.socket", socketPath + " accessible");
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        // The socket is missing, so the daemon is not running: permissions are not the problem
        // (a socket that exists but refuses this user passes this check and fails docker.daemon).
        String fix = os.contains("mac")
                ? "Open Docker Desktop — the socket is created when Docker Desktop is running"
                : "Start the Docker daemon ('sudo systemctl start docker', or open Docker Desktop)";
        return CheckResult.fail("docker.socket", socketPath + " not found", fix);
    }
}
