package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Printer;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

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

    @Option(names = {"--namespace"}, description = "Docker resource namespace for the containers this instance launches (default: derived from --container; none for the default container)", paramLabel = "<name>")
    String namespace;

    // Docker's own name rule: the emulators put the namespace inside child container names.
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    @Option(names = {"--pull"}, description = "Image pull policy: always, missing, never", defaultValue = "missing", paramLabel = "always|missing|never")
    String pull;

    /**
     * A {@code StartCommand} with {@code profileName} applied by exactly the machinery a real
     * {@code start} invocation uses: same provider, same precedence, same {@code ${...}}
     * interpolation. Anything that needs to know what a profile would start with must go through
     * here rather than reading the {@link io.floci.cli.config.Profile} bean, or it silently
     * disagrees with {@code start} on any interpolated value.
     *
     * <p>Pass the provider the outer parse used ({@link ProfileDefaultValueProvider#of}): it
     * memoizes the profile it resolved, so this applies that same snapshot instead of reading the
     * file again, and a caller can read the snapshot back through
     * {@link ProfileDefaultValueProvider#resolved()}.
     */
    public static StartCommand resolvedFor(ProductProfile product,
                                           ProfileDefaultValueProvider provider,
                                           String profileName) {
        StartCommand start = new StartCommand(product);
        if (profileName != null) {
            new CommandLine(start)
                    .setCaseInsensitiveEnumValuesAllowed(true)
                    .setDefaultValueProvider(provider)
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
     * The resource namespace this instance passes to the emulator, which scopes the child
     * containers and volumes it launches (Lambda, ECS, Cloud Run, Service Bus, ...) and its
     * startup orphan sweeps. Without it two instances of one emulator remove each other's
     * children. An explicit {@code --namespace} wins; otherwise it is derived from the container
     * ({@link ProductProfile#resourceNamespace(String)}).
     */
    /**
     * Why these settings cannot start, or {@code null}. Checked before anything touches Docker,
     * and by {@code restart} before it stops the running container, so a bad profile value never
     * leaves an instance stopped.
     */
    public String validationError() {
        if (namespace != null && !namespace.isBlank() && !NAMESPACE.matcher(namespace).matches()) {
            return "Invalid namespace '" + namespace + "'.\nUse letters, digits, '_', '.' and '-', starting with a letter or digit.";
        }
        if (port < 1 || port > 65535) {
            return "--port must be between 1 and 65535, but was " + port + ".\nPick a free port, for example "
                    + (profile.defaultPort() + 10000) + ".";
        }
        if (pull == null || !PULL_POLICIES.contains(pull.toLowerCase(Locale.ROOT))) {
            // Anything else used to fall through to a pull, so a typo silently meant "always".
            return "Invalid --pull '" + pull + "'.\nUse always, missing or never.";
        }
        return null;
    }

    private static final List<String> PULL_POLICIES = List.of("always", "missing", "never");

    /**
     * {@code persistDir} as an absolute, normalized path. Docker reads a bind source without a
     * leading slash as a named volume, so {@code --persist ./data} would otherwise keep the state
     * in an anonymous Docker volume instead of the directory the user named.
     */
    public String persistPath() {
        return Path.of(persistDir).toAbsolutePath().normalize().toString();
    }

    /**
     * The bind source handed to a daemon reached through {@code daemon}. A remote daemon gets
     * {@code persistDir} exactly as given: the path names a directory on its machine, and
     * resolving it here would apply this machine's rules to it (a Windows CLI turns
     * {@code /srv/data} into a drive path). Only a local daemon gets {@link #persistPath()}.
     */
    public String bindSource(DockerClient.DockerHost daemon) {
        return daemon.kind() == DockerClient.Kind.TCP ? persistDir : persistPath();
    }

    /**
     * Creates the state directory, returning why it could not be created, or {@code null}. Done by
     * the CLI rather than docker: a bind source docker creates is owned by root on Linux, and the
     * emulator then cannot write to it. Restart calls this before it stops anything, so a
     * directory that cannot be created leaves the running instance alone.
     *
     * <p>An existing directory is accepted as it is, with no write check: the emulator runs as its
     * own user (uid 1001) and the AWS image re-owns its data directory at startup, so whether the
     * CLI's user can write there says nothing about whether the emulator can. The emulator reports
     * a directory it cannot write to itself.
     */
    public String preparePersistDir() {
        return preparePersistDir(DockerClient.dockerHost());
    }

    /** {@link #preparePersistDir()} for a daemon reached through {@code daemon}; the test seam. */
    public String preparePersistDir(DockerClient.DockerHost daemon) {
        if (persistDir == null || persistDir.isBlank()) return null;
        // A remote daemon reads the bind source on its own machine, where this CLI can neither
        // create nor check it; it is passed on untouched (bindSource), so nothing here can fail
        // on it later either.
        if (daemon.kind() == DockerClient.Kind.TCP) return null;
        Path path;
        try {
            path = Path.of(persistPath());
        } catch (InvalidPathException e) {
            // Names persistDir as given: resolving it is what failed.
            return "Invalid persist directory '" + persistDir + "': " + e.getReason()
                    + ".\nPass a --persist path that is valid on this system.";
        }
        try {
            Files.createDirectories(path);
            return null;
        } catch (Exception e) {
            return "Could not create the persist directory " + path + ": " + e.getMessage()
                    + "\nPass a --persist directory you can write to.";
        }
    }

    public String resourceNamespace() {
        return resourceNamespaceFor(global.container);
    }

    /** {@link #resourceNamespace()} for an instance in {@code container}, which a flag may have overridden. */
    public String resourceNamespaceFor(String container) {
        if (namespace != null && !namespace.isBlank()) return namespace;
        return profile.resourceNamespace(container);
    }

    /**
     * The {@code docker run} arguments this invocation would use. Extracted from {@link #call()}
     * as a test seam so the persistence mount can be pinned without starting a container;
     * {@code socketArgs} is a parameter because {@link DockerClient#dockerSocketRunArgs()} reads
     * the ambient {@code DOCKER_HOST} and so differs per machine.
     */
    public List<String> dockerRunArgs(List<String> socketArgs) {
        return dockerRunArgs(socketArgs, LOCAL_DAEMON);
    }

    private static final DockerClient.DockerHost LOCAL_DAEMON =
            new DockerClient.DockerHost(DockerClient.Kind.UNIX, null, null);

    /** {@link #dockerRunArgs(List)} for a daemon reached through {@code daemon}, which decides the bind source. */
    public List<String> dockerRunArgs(List<String> socketArgs, DockerClient.DockerHost daemon) {
        List<String> args = new ArrayList<>();
        args.addAll(List.of("-d", "--name", global.container));
        args.addAll(List.of("-p", port + ":" + profile.defaultPort()));
        args.addAll(socketArgs);
        if (persistDir != null && !persistDir.isBlank()) {
            args.addAll(List.of("-v", bindSource(daemon) + ":/app/data"));
            // The server defaults to in-memory storage; enable persistent mode so
            // state is actually written to the mounted directory and survives restarts.
            args.addAll(List.of("-e", profile.envVar("STORAGE_MODE") + "=persistent"));
        }
        if (services != null && !services.isBlank()) {
            args.addAll(List.of("-e", profile.envVar("SERVICES") + "=" + services));
        }
        // URLs the emulator hands back (SQS QueueUrl, presigned URLs, the OCI invoke endpoint)
        // are built from its base URL, which defaults to localhost and the product port. On any
        // other host port they would point at whichever instance owns the default one; the host
        // comes from the endpoint, so a remote Docker host is named too.
        if (port != profile.defaultPort()) {
            args.addAll(List.of("-e", profile.envVar("BASE_URL") + "=" + withPort(global.endpoint, port)));
        }
        String ns = resourceNamespace();
        if (ns != null) {
            args.addAll(List.of("-e", profile.envVar("DOCKER_RESOURCE_NAMESPACE") + "=" + ns));
        }
        new TreeMap<>(profile.startEnv()).forEach((suffix, value) ->
                args.addAll(List.of("-e", profile.envVar(suffix) + "=" + value)));
        args.add(image);
        return args;
    }

    // DockerClient reports a missing binary as "docker binary not found"; on Windows the JDK says
    // "Cannot run program" instead.
    static boolean dockerMissing(DockerException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("docker binary not found") || message.contains("Cannot run program");
    }

    /** {@code endpoint} with its port replaced, or a localhost URL if it cannot be parsed. */
    public static String withPort(String endpoint, int port) {
        try {
            URI uri = URI.create(endpoint);
            if (uri.getHost() == null) return "http://localhost:" + port;
            // Raw components: the decoding getters would turn ?token=a%26b into ?token=a&b.
            StringBuilder url = new StringBuilder(uri.getScheme()).append("://");
            if (uri.getRawUserInfo() != null) url.append(uri.getRawUserInfo()).append('@');
            url.append(uri.getHost()).append(':').append(port); // getHost keeps IPv6 brackets
            if (uri.getRawPath() != null) url.append(uri.getRawPath());
            if (uri.getRawQuery() != null) url.append('?').append(uri.getRawQuery());
            if (uri.getRawFragment() != null) url.append('#').append(uri.getRawFragment());
            return url.toString();
        } catch (Exception e) {
            return "http://localhost:" + port;
        }
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();
        DockerClient docker = new DockerClient();

        String invalid = validationError();
        if (invalid != null) {
            printer.error(invalid);
            return 2;
        }

        String unwritable = preparePersistDir();
        if (unwritable != null) {
            printer.error(unwritable);
            return 1;
        }

        // Check if container already exists. This is also the first docker call, so a missing
        // binary shows up here, without a separate `docker --version` probe on every start.
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
            if (dockerMissing(e)) {
                printer.error("docker binary not found in PATH.\nInstall Docker Desktop from https://docs.docker.com/get-docker/");
                return 1;
            }
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

        List<String> args = dockerRunArgs(DockerClient.dockerSocketRunArgs(), DockerClient.dockerHost());

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
        wait.knownEndpoint = global.endpoint;
        wait.timeout = "30s";
        return wait.call();
    }
}
