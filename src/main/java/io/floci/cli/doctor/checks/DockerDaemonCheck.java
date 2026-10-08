package io.floci.cli.doctor.checks;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;

public class DockerDaemonCheck implements Check {

    private final DockerClient docker;

    public DockerDaemonCheck() {
        this(new DockerClient());
    }

    /** {@code docker} is shared across one doctor run so each docker fact is fetched once. */
    public DockerDaemonCheck(DockerClient docker) {
        this.docker = docker;
    }

    @Override
    public String name() {
        return "docker.daemon";
    }

    @Override
    public CheckResult run(String endpoint, String container) {
        if (docker.isDaemonReachable()) {
            return CheckResult.ok("docker.daemon", "Daemon reachable");
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        String fix = os.contains("mac")
                ? "Start Docker Desktop from your Applications folder"
                : "Start Docker: sudo systemctl start docker";
        return CheckResult.fail("docker.daemon", "Docker daemon not reachable", fix);
    }
}
