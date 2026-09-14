package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Printer;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "start",
        description = "Start the Floci AWS emulator container",
        mixinStandardHelpOptions = true
)
public class StartCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public StartCommand() {
        this(ProductProfile.AWS);
    }

    protected StartCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
        this.port = profile.defaultPort();
        this.image = profile.defaultImageRef();
    }

    @Option(names = {"--port"}, description = "Host port to bind (default: ${DEFAULT-VALUE})", paramLabel = "<port>")
    int port;

    @Option(names = {"--persist"}, description = "Host directory for persistent state", paramLabel = "<dir>")
    String persistDir;

    @Option(names = {"--services"}, description = "Comma-separated list of services to enable", paramLabel = "<csv>")
    String services;

    @Option(names = {"--detach"}, description = "Return immediately without waiting for readiness")
    boolean detach;

    @Option(names = {"--image"}, description = "Image reference to use (default: ${DEFAULT-VALUE})", paramLabel = "<ref>")
    String image;

    @Option(names = {"--pull"}, description = "Image pull policy: always, missing, never", defaultValue = "missing", paramLabel = "always|missing|never")
    String pull;

    /**
     * A {@code StartCommand} with {@code profileName} applied by exactly the machinery a real
     * {@code start} invocation uses: same provider, same precedence, same {@code ${...}}
     * interpolation. Anything that needs to know what a profile would start with must go through
     * here rather than reading the {@link io.floci.cli.config.Profile} bean, or it silently
     * disagrees with {@code start} on any interpolated value.
     */
    public static StartCommand resolvedFor(ProductProfile product, ProfileStore store, String profileName) {
        StartCommand start = new StartCommand(product);
        if (profileName != null) {
            new CommandLine(start)
                    .setCaseInsensitiveEnumValuesAllowed(true)
                    .setDefaultValueProvider(new ProfileDefaultValueProvider(store))
                    .parseArgs("--profile", profileName);
        }
        return start;
    }

    /** What a profile-resolved instance would start with. Read by {@code config show}. */
    public String image() { return image; }

    public int port() { return port; }

    public String persistDir() { return persistDir; }

    public String services() { return services; }

    /**
     * The {@code docker run} arguments this invocation would use. Extracted from {@link #call()}
     * as a test seam so the persistence mount can be pinned without starting a container;
     * {@code socketArgs} is a parameter because {@link DockerClient#dockerSocketRunArgs()} reads
     * the ambient {@code DOCKER_HOST} and so differs per machine.
     */
    public List<String> dockerRunArgs(List<String> socketArgs) {
        List<String> args = new ArrayList<>();
        args.addAll(List.of("-d", "--name", global.container));
        args.addAll(List.of("-p", port + ":" + profile.defaultPort()));
        args.addAll(socketArgs);
        if (persistDir != null && !persistDir.isBlank()) {
            args.addAll(List.of("-v", persistDir + ":/app/data"));
            // The server defaults to in-memory storage; enable persistent mode so
            // state is actually written to the mounted directory and survives restarts.
            args.addAll(List.of("-e", profile.envVar("STORAGE_MODE") + "=persistent"));
        }
        if (services != null && !services.isBlank()) {
            args.addAll(List.of("-e", profile.envVar("SERVICES") + "=" + services));
        }
        args.add(image);
        return args;
    }

    /** {@code endpoint} with its port replaced, or a localhost URL if it cannot be parsed. */
    public static String withPort(String endpoint, int port) {
        try {
            URI uri = URI.create(endpoint);
            if (uri.getHost() == null) return "http://localhost:" + port;
            return new URI(uri.getScheme(), null, uri.getHost(), port, uri.getPath(), null, null).toString();
        } catch (Exception e) {
            return "http://localhost:" + port;
        }
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();
        DockerClient docker = new DockerClient();

        // Verify docker is available
        if (!DockerClient.isInstalled()) {
            printer.error("docker binary not found in PATH.\nInstall Docker Desktop from https://docs.docker.com/get-docker/");
            return 1;
        }

        // Check if container already exists
        try {
            var existing = docker.inspectContainer(global.container);
            if (existing.isPresent()) {
                String state = existing.get().state();
                if ("running".equals(state)) {
                    printer.error("Container '" + global.container + "' is already running.\nRun '" + profile.commandPrefix() + " stop' first or pass --container <name> to use a different name.");
                    return 1;
                }
                // Remove stopped container so we can start fresh
                printer.println(Ansi.gray("Removing stopped container '" + global.container + "'..."));
                docker.removeContainer(global.container);
            }
        } catch (DockerException e) {
            printer.error("Failed to inspect container: " + e.getMessage());
            return 1;
        }

        // Pull image if needed
        try {
            printer.println(Ansi.gray("Checking image " + image + " (policy: " + pull + ")..."));
            docker.pull(image, pull);
        } catch (DockerException e) {
            printer.error("Failed to pull image: " + e.getMessage() + "\nRun '" + profile.commandPrefix() + " start --pull never' to skip pulling.");
            return 1;
        }

        List<String> args = dockerRunArgs(DockerClient.dockerSocketRunArgs());

        try {
            printer.println("Starting " + Ansi.gold(profile.displayName()) + " container...");
            String id = docker.startContainer(args);
            printer.println(Ansi.green("Container started") + " (" + id.substring(0, Math.min(12, id.length())) + ")");
        } catch (DockerException e) {
            printer.error("Failed to start container: " + e.getMessage());
            return 1;
        }

        if (detach) {
            printer.println(Ansi.gray("Detached. Run '" + profile.commandPrefix() + " wait' to poll for readiness."));
            return 0;
        }

        // Point the readiness poll at the port actually bound, keeping whatever host the
        // endpoint already names — a profile or --endpoint may well point somewhere else.
        global.endpoint = withPort(global.endpoint, port);

        // Wait for readiness
        printer.println(Ansi.gray("Waiting for " + profile.displayName() + " to be ready..."));
        WaitCommand wait = new WaitCommand(profile);
        wait.global = global;
        wait.timeout = "30s";
        return wait.call();
    }
}
