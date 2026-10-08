package io.floci.cli.doctor.checks;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;

public class PortAvailableCheck implements Check {

    private final DockerClient docker;

    public PortAvailableCheck() {
        this(new DockerClient());
    }

    /** {@code docker} is shared across one doctor run so each docker fact is fetched once. */
    public PortAvailableCheck(DockerClient docker) {
        this.docker = docker;
    }

    @Override
    public String name() {
        return "port.available";
    }

    @Override
    public CheckResult run(String endpoint, String container) {
        int port = extractPort(endpoint);
        // If a Floci container is already listening on the port, that's fine
        try {
            var info = docker.inspectContainer(container);
            if (info.isPresent() && "running".equals(info.get().state())) {
                return CheckResult.ok("port.available", "Port " + port + " in use by container '" + container + "' (expected)");
            }
        } catch (DockerException ignored) {}

        if (isPortFree(port)) {
            return CheckResult.ok("port.available", "Port " + port + " free");
        }
        return CheckResult.fail("port.available",
                "Port " + port + " is occupied by another process",
                "Run 'lsof -i :" + port + "' to identify the process, or start Floci with --port <other>");
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static int extractPort(String endpoint) {
        try {
            int port = URI.create(endpoint).getPort();
            return port > 0 ? port : 4566;
        } catch (Exception e) {
            return 4566;
        }
    }
}
