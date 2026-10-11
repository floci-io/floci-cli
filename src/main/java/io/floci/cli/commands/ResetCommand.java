package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.http.FlociException;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Glyphs;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

import java.io.Console;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.UnaryOperator;

@Command(
        name = "reset",
        description = "Delete every resource in the running Floci AWS instance, keeping it running",
        mixinStandardHelpOptions = true
)
public class ResetCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    @Option(names = {"--yes", "-y"}, description = "Reset without asking for confirmation")
    boolean yes;

    public ResetCommand() {
        this(ProductProfile.AWS);
    }

    protected ResetCommand(ProductProfile profile) {
        this(profile, new DockerClient(), Prompt.console());
    }

    /** Test seam: {@code docker} answers the container lookup and {@code prompt} the confirmation. */
    public ResetCommand(ProductProfile profile, DockerClient docker, Prompt prompt) {
        this(profile, docker, prompt, System::getenv);
    }

    /** Test seam: {@code env} answers whether {@code <PREFIX>_CONTAINER} selected the container. */
    public ResetCommand(ProductProfile profile, DockerClient docker, Prompt prompt, UnaryOperator<String> env) {
        this.profile = profile;
        this.global = new GlobalOptions(profile, env);
        this.docker = docker;
        this.prompt = prompt;
        this.env = env;
    }

    private final DockerClient docker;
    private final Prompt prompt;
    private final UnaryOperator<String> env;

    @Spec
    CommandSpec spec;

    /** The person at the terminal, when there is one. */
    public interface Prompt {

        /** False when nobody can answer (a pipe, CI). */
        boolean available();

        /** The answer to {@code question}, or null at end of input. */
        String ask(String question);

        static Prompt console() {
            return new Prompt() {
                @Override
                public boolean available() {
                    Console console = System.console();
                    return console != null && console.isTerminal();
                }

                @Override
                public String ask(String question) {
                    return System.console().readLine("%s", question);
                }
            };
        }
    }

    /** The server a reset will wipe, or why none could be settled on. Exactly one is set. */
    public record Target(String endpoint, String error) {}

    /**
     * Where this reset acts. Reset deletes data, so unlike the read-only commands it never
     * guesses. In order:
     * <ol>
     *   <li>A typed {@code --endpoint} is used as given.</li>
     *   <li>A typed {@code --container} must be found running, and is reset.</li>
     *   <li>A container that a profile or {@code <PREFIX>_CONTAINER} supplies must be found
     *       running too. If the profile or environment also names an endpoint other than the
     *       default, the two have to be the same server.</li>
     *   <li>With no container supplied, an endpoint from a profile or the environment is used,
     *       unless a container of the default name runs here and is a different server.</li>
     *   <li>With nothing selected at all, the default container is looked up, falling back to
     *       the default endpoint, which names the same default instance.</li>
     * </ol>
     * Whenever two things that were configured point at different servers, nothing is reset.
     */
    public Target target() {
        if (global.typed("--endpoint")) return new Target(global.endpoint, null);
        boolean typedContainer = global.typed("--container");
        boolean chosen = typedContainer || containerSupplied();
        boolean endpointNamed = namesAServer(global.endpoint, profile);
        String how = "\nRun '" + profile.commandPrefix() + " status" + global.instanceSelector()
                + "' to check it, or pass --endpoint <url> to name the server to reset.";

        Optional<DockerClient.ContainerInfo> info;
        try {
            info = docker.inspectContainer(global.container);
        } catch (DockerException e) {
            if (chosen) {
                return new Target(null, "Could not look up container '" + global.container + "': " + e.getMessage() + how);
            }
            info = Optional.empty(); // nothing known to contradict what was configured
        }
        boolean running = info.isPresent() && "running".equals(info.get().state());
        // By the emulator's own port inside the container, which never changes; the configured
        // endpoint names the host port, and that differs on every instance but the default one.
        Optional<String> published = running
                ? GlobalOptions.publishedEndpoint(info.get().ports(), profile.defaultPort())
                : Optional.empty();

        if (chosen) {
            if (info.isEmpty()) {
                return new Target(null, "Container '" + global.container + "' not found, so nothing was reset." + how);
            }
            if (!running) {
                return new Target(null, "Container '" + global.container + "' is not running (state: "
                        + info.get().state() + "), so nothing was reset." + how);
            }
            if (published.isEmpty()) {
                // No port to find it by: the configured endpoint may well be another instance.
                return new Target(null, "Container '" + global.container
                        + "' publishes no port for the emulator, so nothing was reset." + how);
            }
        }
        if (published.isEmpty()) {
            // Nothing runs here under that name: the configured endpoint is all there is.
            return new Target(global.endpoint, null);
        }

        DockerClient.DockerHost daemon = docker.daemonHost();
        boolean remote = daemon.kind() == DockerClient.Kind.TCP;
        if (endpointNamed && !typedContainer) {
            // A container and an endpoint were both configured: they have to be one server.
            if (sameServer(published.get(), global.endpoint, daemon)) return new Target(global.endpoint, null);
            return new Target(null, "The configured endpoint is " + global.endpoint + ", but container '"
                    + global.container + "' is running at " + whereItRuns(published.get(), daemon)
                    + ". They are different servers, so nothing was reset."
                    + "\nPass --endpoint <url> or --container <name> to say which one to reset.");
        }
        if (remote) {
            // The port was published on the daemon's machine. 'localhost' here would be this
            // one, and whatever answers on that port here is not the container that was found.
            return new Target(null, "Container '" + global.container + "' runs on the remote Docker host "
                    + daemon.raw() + ", so its address is not known here and nothing was reset."
                    + "\nPass --endpoint <url> with the address the emulator is reached at.");
        }
        return new Target(published.get(), null);
    }

    // Whether something other than the default name picked the container: a profile that sets
    // one (every created profile does), <PREFIX>_CONTAINER, or simply a different name.
    private boolean containerSupplied() {
        if (!profile.defaultContainer().equals(global.container)) return true;
        if (env.apply(profile.envVar("CONTAINER")) != null) return true;
        return global.profile != null
                && ProfileDefaultValueProvider.of(spec).resolved().map(p -> p.container != null).orElse(false);
    }

    private static String whereItRuns(String published, DockerClient.DockerHost daemon) {
        return daemon.kind() == DockerClient.Kind.TCP
                ? "port " + URI.create(published).getPort() + " of the remote Docker host " + daemon.raw()
                : published;
    }

    /**
     * Whether {@code configured} is the server a container publishes at {@code published}
     * ({@code http://localhost:<port>} as docker reports it). The port has to match. For a local
     * daemon the configured host has to mean this machine; for a remote one it has to be the
     * daemon's host, since that is where the port was published.
     */
    public static boolean sameServer(String published, String configured, DockerClient.DockerHost daemon) {
        try {
            URI there = URI.create(withoutTrailingSlashes(configured));
            if (URI.create(published).getPort() != there.getPort()) return false;
            String host = there.getHost() == null ? "" : there.getHost().toLowerCase(Locale.ROOT);
            if (daemon.kind() == DockerClient.Kind.TCP) {
                String daemonHost = URI.create(daemon.raw()).getHost();
                return daemonHost != null && daemonHost.equalsIgnoreCase(host);
            }
            return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
                    || host.equals("::1") || host.equals("0.0.0.0") || host.equals("localhost.floci.io");
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    /**
     * Whether {@code endpoint} is something other than the product's default, that is, whether a
     * profile or the environment pointed this command at a particular server. A trailing slash
     * does not make it a different one.
     */
    public static boolean namesAServer(String endpoint, ProductProfile product) {
        return endpoint != null && !withoutTrailingSlashes(endpoint).equals(product.defaultEndpoint());
    }

    private static String withoutTrailingSlashes(String url) {
        String trimmed = url.strip();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();
        Target target = target();
        if (target.error() != null) {
            printer.error(target.error());
            return 1;
        }
        String effectiveEndpoint = target.endpoint();

        if (!yes) {
            if (!prompt.available()) {
                // Nobody to ask: a script must say so explicitly.
                printer.error("reset deletes every resource in " + profile.displayName() + " and needs confirmation.\n"
                        + "Pass --yes to reset without asking.");
                return 2;
            }
            String answer = prompt.ask("Delete every resource in " + profile.displayName()
                    + " at " + effectiveEndpoint + "? [y/N] ");
            if (answer == null || !(answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"))) {
                printer.println("Reset cancelled.");
                return 1;
            }
        }

        try {
            new FlociHttpClient(effectiveEndpoint, profile).resetState();
        } catch (FlociException e) {
            if (e.endpointMissing()) {
                printer.error("Resetting state is not available on this " + profile.displayName() + " server version.\n"
                        + "Restart it without --persist for a clean state, or track progress: " + profile.serverRepo() + "/issues");
            } else {
                // Connection errors already say what to do; others get the status hint.
                printer.error("Failed to reset " + profile.displayName() + ": " + e.getMessage(),
                        "Run '" + profile.commandPrefix() + " status" + global.instanceSelector() + "' to check the emulator.");
            }
            return 1;
        }

        if (printer.format() != OutputFormat.text) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("reset", true);
            out.put("endpoint", effectiveEndpoint);
            printer.structured(out);
            return 0;
        }
        printer.println(Ansi.green(Glyphs.ok()) + " " + profile.displayName() + " state reset" + Ansi.gray("  (" + effectiveEndpoint + ")"));
        return 0;
    }
}
