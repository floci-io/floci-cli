package io.floci.cli.doctor.checks;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.function.BooleanSupplier;

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

    /**
     * False only when {@code port} is known to be taken. A bind that fails on a privileged port
     * may just be this user lacking the right to bind it (Linux below 1024), while the Docker
     * daemon can publish it fine, so there the port counts as taken only if something answers on it.
     */
    public static boolean isPortFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return !takenAfterFailedBind(port, () -> listensOnLoopback(port));
        }
    }

    /** The ports a user may be refused for lack of privilege rather than because they are taken. */
    static final int FIRST_UNPRIVILEGED_PORT = 1024;

    /** What a failed bind of {@code port} means; {@code listening} is asked only when the bind alone cannot tell. */
    public static boolean takenAfterFailedBind(int port, BooleanSupplier listening) {
        return port >= FIRST_UNPRIVILEGED_PORT || listening.getAsBoolean();
    }

    /** Whether something accepts a connection on {@code port} on this machine. */
    public static boolean listensOnLoopback(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
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
