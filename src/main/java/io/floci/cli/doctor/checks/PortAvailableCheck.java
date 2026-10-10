package io.floci.cli.doctor.checks;

import io.floci.cli.ProductProfile;
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
import java.util.Locale;
import java.util.function.BooleanSupplier;

public class PortAvailableCheck implements Check {

    private final DockerClient docker;

    public PortAvailableCheck() {
        this(new DockerClient());
    }

    /** {@code docker} is shared across one doctor run so each docker fact is fetched once. */
    public PortAvailableCheck(DockerClient docker) {
        this(docker, ProductProfile.AWS);
    }

    /** Falls back to {@code product}'s default port and names its tree in the hint. */
    public PortAvailableCheck(DockerClient docker, ProductProfile product) {
        this.docker = docker;
        this.defaultPort = product.defaultPort();
        this.startCommand = product.commandPrefix() + " start";
    }

    private final int defaultPort;
    private final String startCommand;

    @Override
    public String name() {
        return "port.available";
    }

    @Override
    public CheckResult run(String endpoint, String container) {
        int port = extractPort(endpoint, defaultPort);
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
                "Run 'lsof -i :" + port + "' to identify the process, or '" + startCommand + " --port <other>'.");
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
            return !takenAfterFailedBind(port, e.getMessage(), () -> listensOnLoopback(port));
        }
    }

    /** The ports a user may be refused for lack of privilege rather than because they are taken. */
    static final int FIRST_UNPRIVILEGED_PORT = 1024;

    /**
     * What a failed bind of {@code port} means, given the bind's error {@code message}. An
     * "address already in use" error is the answer on any port; {@code listening} is asked only
     * for a privileged port whose error says something else (permission denied).
     */
    public static boolean takenAfterFailedBind(int port, String message, BooleanSupplier listening) {
        if (port >= FIRST_UNPRIVILEGED_PORT) return true;
        if (message != null && message.toLowerCase(Locale.ROOT).contains("in use")) return true;
        return listening.getAsBoolean();
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
        return extractPort(endpoint, ProductProfile.AWS.defaultPort());
    }

    /** The endpoint's port, or {@code fallback} when it names none or cannot be parsed. */
    public static int extractPort(String endpoint, int fallback) {
        try {
            int port = URI.create(endpoint).getPort();
            return port > 0 ? port : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
