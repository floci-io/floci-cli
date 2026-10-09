package io.floci.cli.doctor.checks;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;

public class DockerVersionCheck implements Check {

    private final DockerClient docker;

    public DockerVersionCheck() {
        this(new DockerClient());
    }

    /** {@code docker} is shared across one doctor run so each docker fact is fetched once. */
    public DockerVersionCheck(DockerClient docker) {
        this.docker = docker;
    }

    @Override
    public String name() {
        return "docker.version";
    }

    private static final int MIN_MAJOR = 20;
    private static final int MIN_MINOR = 10;

    @Override
    public CheckResult run(String endpoint, String container) {
        try {
            String raw = docker.dockerVersion();
            if (meetsMinimum(raw)) {
                return CheckResult.ok("docker.version", "Docker " + raw + " (>= 20.10)");
            }
            return CheckResult.warn("docker.version",
                    "Docker " + raw + " is below the recommended minimum (20.10)",
                    "Upgrade Docker: https://docs.docker.com/engine/install/");
        } catch (DockerException e) {
            return CheckResult.warn("docker.version", "Could not determine Docker version", null);
        }
    }

    public static boolean meetsMinimum(String version) {
        try {
            String[] parts = version.split("[.\\-]");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > MIN_MAJOR || (major == MIN_MAJOR && minor >= MIN_MINOR);
        } catch (NumberFormatException e) {
            return true; // assume ok if we can't parse
        }
    }
}
