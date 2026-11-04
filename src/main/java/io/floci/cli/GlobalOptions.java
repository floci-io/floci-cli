package io.floci.cli;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Glyphs;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Shared global options for every product tree, parameterized by {@link ProductProfile}.
 *
 * The per-product defaults for {@code --endpoint} and {@code --container} are set as
 * field initial values in the constructor (env var override included) instead of
 * annotation {@code defaultValue}s — annotation strings are compile-time constants and
 * cannot vary by product. Picocli keeps a parsed-over field's initial value when the
 * option has no defaultValue, and renders it for {@code ${DEFAULT-VALUE}} in help.
 *
 * Commands must pre-initialize their {@code @Mixin GlobalOptions} field in their
 * constructor (picocli uses a non-null field instance as-is); a command that forgets
 * gets AWS defaults — covered by the per-tree parse tests.
 */
public class GlobalOptions {

    /** System property that {@code --verbose} sets; read where no GlobalOptions is at hand. */
    public static final String VERBOSE_PROPERTY = "floci.verbose";

    // The command this mixin belongs to, so hints can tell which flags were actually typed.
    // Null for commands built by hand (restart's start), which were never parsed.
    @Spec(Spec.Target.MIXEE)
    CommandSpec mixee;

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "localhost.floci.io");

    private static final Pattern PLAIN_WORD = Pattern.compile("[A-Za-z0-9._@%+=:,/-]+");

    public final ProductProfile product;

    // The value FLOCI_ENDPOINT supplied, if any: that is a choice, like typing --endpoint.
    private final String endpointFromEnv;

    @Option(names = {"--endpoint"},
            description = "Floci server endpoint URL (default: ${DEFAULT-VALUE})",
            paramLabel = "<url>")
    public String endpoint;

    @Option(names = {"--container"},
            description = "Floci container name (default: ${DEFAULT-VALUE})",
            paramLabel = "<name>")
    public String container;

    @Option(names = {"--profile"},
            description = "Config profile from ~/.floci/profiles/",
            paramLabel = "<name>")
    public String profile;

    @Option(names = {"--output", "-o"},
            description = "Output format: text, json, yaml",
            defaultValue = "text",
            paramLabel = "text|json|yaml")
    public OutputFormat output;

    @Option(names = {"--json"}, description = "Shorthand for --output json")
    public boolean json;

    @Option(names = {"--quiet", "-q"}, description = "Suppress non-error output")
    public boolean quiet;

    @Option(names = {"--verbose", "-v"}, description = "Debug logging to stderr")
    public boolean verbose;

    @Option(names = {"--no-color"}, description = "Disable ANSI colors")
    public boolean noColor;

    @Option(names = {"--no-emoji"}, description = "Plain ASCII marks instead of Unicode ones (the default when output is piped)")
    public boolean noEmoji;

    public GlobalOptions() {
        this(ProductProfile.AWS);
    }

    public GlobalOptions(ProductProfile product) {
        this(product, System::getenv);
    }

    /** Test seam: {@code envLookup} replaces {@code System.getenv}. */
    public GlobalOptions(ProductProfile product, UnaryOperator<String> envLookup) {
        this.product = product;
        this.endpointFromEnv = envLookup.apply(product.envVar("ENDPOINT"));
        this.endpoint = envOr(envLookup, product.envVar("ENDPOINT"), product.defaultEndpoint());
        this.container = envOr(envLookup, product.envVar("CONTAINER"), product.defaultContainer());
    }

    // Matches picocli's ${VAR:-fallback}: only a NULL env var falls back — empty counts as set.
    private static String envOr(UnaryOperator<String> env, String var, String fallback) {
        String value = env.apply(var);
        return value != null ? value : fallback;
    }

    /**
     * The flags that select this instance again in a hint string: {@code " --profile x"} when a
     * profile was used (plus {@code " --container y"} when that was typed too, since it overrides
     * the profile's container), {@code " --container y"} for a non-default container, else
     * empty. Values are shell-quoted, because hints are meant to be pasted.
     */
    public String instanceSelector() {
        if (profile != null) {
            String selector = " --profile " + shellWord(profile);
            return typed("--container") ? selector + " --container " + shellWord(container) : selector;
        }
        if (!product.defaultContainer().equals(container)) return " --container " + shellWord(container);
        return "";
    }

    /** Whether {@code option} was given on the command line, as opposed to coming from a profile, the environment or the default. */
    public boolean typed(String option) {
        try {
            return mixee != null && mixee.commandLine() != null
                    && mixee.commandLine().getParseResult() != null
                    && mixee.commandLine().getParseResult().hasMatchedOption(option);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // POSIX single quotes: the hints are shell command lines like the CLI's other 'Run ...' hints.
    private static String shellWord(String value) {
        if (PLAIN_WORD.matcher(value).matches()) return value;
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public Printer printer() {
        if (noColor || !isStdoutTty()) {
            Ansi.disable();
        }
        // Read by the exception handler (stack traces) and DockerClient (the commands it runs).
        if (verbose) {
            System.setProperty(VERBOSE_PROPERTY, "true");
        }
        if (plainGlyphs(noEmoji, isStdoutTty(), System.getenv("NO_COLOR") != null)) {
            Glyphs.plain();
        }
        return new Printer(System.out, System.err, format(), quiet);
    }

    /** The output format in effect: {@code --json} is {@code --output json} and wins over it. */
    public OutputFormat format() {
        return json ? OutputFormat.json : output;
    }

    /**
     * Whether marks are printed as plain ASCII: on request, when nobody is looking at a terminal
     * (a pipe, a file, CI logs), or when NO_COLOR asks for undecorated output.
     */
    public static boolean plainGlyphs(boolean noEmoji, boolean terminal, boolean noColorEnv) {
        return noEmoji || !terminal || noColorEnv;
    }

    /** Whether a configured endpoint can identify this instance without a container lookup. */
    public boolean canUseConfiguredEndpoint() {
        return typed("--endpoint") || endpoint.equals(endpointFromEnv)
                || !product.defaultEndpoint().equals(endpoint)
                || product.defaultContainer().equals(container);
    }

    /**
     * Whether the configured endpoint was chosen on purpose, so it beats the port a local container
     * publishes: typed, from the environment, or on another host. A loopback endpoint from a
     * profile is only where the instance was last known to be, so the container corrects it.
     */
    public boolean configuredEndpointWins() {
        return typed("--endpoint") || endpoint.equals(endpointFromEnv) || !isLoopback(endpoint);
    }

    private static boolean isLoopback(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null || LOOPBACK_HOSTS.contains(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * For commands that act on the instance (env, services, snapshot, az setup): where it is, or
     * an {@link IllegalStateException} with the next step when that cannot be known, because
     * acting on the default instance instead could change the wrong state.
     */
    public String resolvedEndpoint(DockerClient docker) {
        return resolvedEndpoint(docker, message -> { });
    }

    /** As above, reporting a missing or stopped container on stderr so env's stdout stays clean. */
    public String resolvedEndpoint(DockerClient docker, Consumer<String> warning) {
        Lookup found = lookup(docker);
        if (found.problem() != null) warning.accept(found.problem());
        if (found.endpoint() != null) return found.endpoint();
        throw new IllegalStateException("The endpoint for container '" + container + "' is unknown.\n"
                + (found.dockerFailed()
                        ? "Check that Docker is running and retry, or pass --endpoint <url> explicitly."
                        : "Run '" + product.commandPrefix() + " start" + instanceSelector()
                                + "', or pass --endpoint <url> explicitly."));
    }

    /**
     * For commands that only report (doctor, version, wait): where the instance is, or empty when
     * that cannot be known. They must still run when the instance is missing, without describing
     * the default instance as if it were this one.
     */
    public Optional<String> knownEndpoint(DockerClient docker) {
        return Optional.ofNullable(lookup(docker).endpoint());
    }

    private record Lookup(String endpoint, String problem, boolean dockerFailed) { }

    private Lookup lookup(DockerClient docker) {
        if (configuredEndpointWins()) return new Lookup(endpoint, null, false);
        String configured = canUseConfiguredEndpoint() ? endpoint : null;
        try {
            var info = docker.inspectContainer(container);
            if (info.isEmpty()) return new Lookup(configured, instanceNotRunning(), false);
            String problem = "running".equals(info.get().state()) ? null : instanceNotRunning();
            String published = endpointFromPorts(info.get().ports(), null);
            return new Lookup(published != null ? published : configured, problem, false);
        } catch (DockerException | RuntimeException e) {
            return new Lookup(configured,
                    "Could not inspect container '" + container + "'.\nCheck that Docker is running and retry.", true);
        }
    }

    private String instanceNotRunning() {
        return "Container '" + container + "' is not running.\nRun '" + product.commandPrefix()
                + " start" + instanceSelector() + "' before using its environment.";
    }

    // Published mappings always name the product's internal port, even when a profile's
    // configured endpoint already contains its custom host port.
    public String endpointFromPorts(String ports, String fallback) {
        return publishedEndpoint(ports, product.defaultPort()).orElse(fallback);
    }

    /**
     * Where a container with these published {@code ports} ("14566->4566/tcp ...") is reached for
     * its {@code containerPort}, or empty when that port is not published. For callers that must
     * not fall back to a configured endpoint.
     */
    public static Optional<String> publishedEndpoint(String ports, int containerPort) {
        if (ports == null || ports.isBlank()) return Optional.empty();
        for (String mapping : ports.trim().split("\\s+")) {
            int arrow = mapping.indexOf("->");
            if (arrow < 0) continue;
            String hostPort = mapping.substring(0, arrow);
            String rest = mapping.substring(arrow + 2);
            String cPort = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
            if (String.valueOf(containerPort).equals(cPort)) {
                return Optional.of("http://localhost:" + hostPort);
            }
        }
        return Optional.empty();
    }

    private static boolean isStdoutTty() {
        return System.console() != null;
    }
}
