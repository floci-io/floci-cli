package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.http.FlociException;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Glyphs;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.io.Console;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

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
        this.profile = profile;
        this.global = new GlobalOptions(profile);
        this.docker = docker;
        this.prompt = prompt;
    }

    private final DockerClient docker;
    private final Prompt prompt;

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
     * guesses: an {@code --endpoint} the user typed is used as given, and a container the user
     * chose must be found running.
     *
     * <p>An endpoint that a profile or {@code <PREFIX>_ENDPOINT} supplies names a server just as
     * a typed one does. With no container chosen it is used as given too: looking up the default
     * container instead, as {@link GlobalOptions#resolvedEndpoint} does, would reset whatever
     * runs locally under the default name rather than the server the user pointed at. Only when
     * nothing at all was selected is the default container looked up, falling back to the
     * default endpoint, which names the same default instance.
     */
    public Target target() {
        if (global.typed("--endpoint")) return new Target(global.endpoint, null);
        boolean chosen = global.typed("--container") || !profile.defaultContainer().equals(global.container);
        if (!chosen && namesAServer(global.endpoint, profile)) return new Target(global.endpoint, null);
        String how = "\nRun '" + profile.commandPrefix() + " status" + global.instanceSelector()
                + "' to check it, or pass --endpoint <url> to name the server to reset.";
        Optional<DockerClient.ContainerInfo> info;
        try {
            info = docker.inspectContainer(global.container);
        } catch (DockerException e) {
            if (chosen) {
                return new Target(null, "Could not look up container '" + global.container + "': " + e.getMessage() + how);
            }
            info = Optional.empty();
        }
        if (info.isEmpty()) {
            return chosen
                    ? new Target(null, "Container '" + global.container + "' not found, so nothing was reset." + how)
                    : new Target(global.endpoint, null);
        }
        if (chosen && !"running".equals(info.get().state())) {
            return new Target(null, "Container '" + global.container + "' is not running (state: "
                    + info.get().state() + "), so nothing was reset." + how);
        }
        // By the emulator's own port inside the container, which never changes; the configured
        // endpoint names the host port, and that differs on every instance but the default one.
        Optional<String> published = GlobalOptions.publishedEndpoint(info.get().ports(), profile.defaultPort());
        if (chosen && published.isEmpty()) {
            // No port to find it by: the configured endpoint may well be another instance.
            return new Target(null, "Container '" + global.container
                    + "' publishes no port for the emulator, so nothing was reset." + how);
        }
        return new Target(published.orElse(global.endpoint), null);
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
