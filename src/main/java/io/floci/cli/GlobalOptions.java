package io.floci.cli;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.net.URI;
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

    private static final Pattern PLAIN_WORD = Pattern.compile("[A-Za-z0-9._@%+=:,/-]+");

    public final ProductProfile product;

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

    @Option(names = {"--quiet", "-q"}, description = "Suppress non-error output")
    public boolean quiet;

    @Option(names = {"--verbose", "-v"}, description = "Debug logging to stderr")
    public boolean verbose;

    @Option(names = {"--no-color"}, description = "Disable ANSI colors")
    public boolean noColor;

    public GlobalOptions() {
        this(ProductProfile.AWS);
    }

    public GlobalOptions(ProductProfile product) {
        this(product, System::getenv);
    }

    /** Test seam: {@code envLookup} replaces {@code System.getenv}. */
    public GlobalOptions(ProductProfile product, UnaryOperator<String> envLookup) {
        this.product = product;
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

    private boolean typed(String option) {
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
        return new Printer(System.out, System.err, output, quiet);
    }

    // Inspects the container and derives the endpoint from its host port mapping.
    // Falls back to the configured endpoint if the container is not found or has no mapping.
    public String resolvedEndpoint(DockerClient docker) {
        try {
            return docker.inspectContainer(container)
                    .map(info -> endpointFromPorts(info.ports(), endpoint))
                    .orElse(endpoint);
        } catch (Exception e) {
            return endpoint;
        }
    }

    // Matches the container-side port from the configured endpoint to find the actual host port.
    public String endpointFromPorts(String ports, String fallback) {
        if (ports == null || ports.isBlank()) return fallback;
        try {
            int containerPort = URI.create(fallback).getPort();
            if (containerPort == -1) containerPort = product.defaultPort();
            for (String mapping : ports.trim().split("\\s+")) {
                int arrow = mapping.indexOf("->");
                if (arrow < 0) continue;
                String hostPort = mapping.substring(0, arrow);
                String rest = mapping.substring(arrow + 2);
                String cPort = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
                if (String.valueOf(containerPort).equals(cPort)) {
                    return "http://localhost:" + hostPort;
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static boolean isStdoutTty() {
        return System.console() != null;
    }
}
