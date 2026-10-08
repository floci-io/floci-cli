package io.floci.cli.doctor.checks;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;

public class DockerInstalledCheck implements Check {

    private final DockerClient docker;

    public DockerInstalledCheck() {
        this(new DockerClient());
    }

    /** {@code docker} is shared across one doctor run so each docker fact is fetched once. */
    public DockerInstalledCheck(DockerClient docker) {
        this.docker = docker;
    }

    @Override
    public String name() {
        return "docker.installed";
    }

    @Override
    public CheckResult run(String endpoint, String container) {
        // The version query is shared with docker.version; only when it fails is a separate
        // probe needed to tell "not installed" from "installed, daemon down".
        try {
            String version = docker.dockerVersion();
            return CheckResult.ok("docker.installed", "Docker " + version + " detected");
        } catch (Exception e) {
            if (!DockerClient.isInstalled()) {
                return CheckResult.fail("docker.installed",
                        "docker binary not found in PATH",
                        "Install Docker Desktop from https://docs.docker.com/get-docker/");
            }
            return CheckResult.ok("docker.installed", "Docker detected");
        }
    }
}
