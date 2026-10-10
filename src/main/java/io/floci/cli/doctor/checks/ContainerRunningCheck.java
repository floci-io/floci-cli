package io.floci.cli.doctor.checks;

import io.floci.cli.ProductProfile;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;

public class ContainerRunningCheck implements Check {

    private final DockerClient docker;

    public ContainerRunningCheck() {
        this(new DockerClient());
    }

    /** {@code docker} is shared across one doctor run so each docker fact is fetched once. */
    public ContainerRunningCheck(DockerClient docker) {
        this(docker, ProductProfile.AWS);
    }

    /** Hints name {@code product}'s tree ({@code floci gcp start}, ...). */
    public ContainerRunningCheck(DockerClient docker, ProductProfile product) {
        this.docker = docker;
        this.start = product.commandPrefix() + " start";
        this.stop = product.commandPrefix() + " stop";
    }

    private final String start;
    private final String stop;

    @Override
    public String name() {
        return "container.running";
    }

    @Override
    public CheckResult run(String endpoint, String container) {
        try {
            var info = docker.inspectContainer(container);
            if (info.isEmpty()) {
                return CheckResult.warn("container.running",
                        "Container '" + container + "' not found",
                        start);
            }
            String state = info.get().state();
            if ("running".equals(state)) {
                return CheckResult.ok("container.running", "Container '" + container + "' is running");
            }
            return CheckResult.fail("container.running",
                    "Container '" + container + "' exists but state is '" + state + "'",
                    start + "  (or '" + stop + " && " + start + "' to restart)");
        } catch (DockerException e) {
            return CheckResult.warn("container.running", "Could not inspect container: " + e.getMessage(), null);
        }
    }
}
