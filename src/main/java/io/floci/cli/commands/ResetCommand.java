package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.http.FlociException;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.io.Console;
import java.util.LinkedHashMap;
import java.util.Map;
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
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();
        String effectiveEndpoint = global.resolvedEndpoint(new DockerClient());

        if (!yes) {
            Console console = System.console();
            if (console == null || !console.isTerminal()) {
                // Nobody to ask: a script must say so explicitly.
                printer.error("reset deletes every resource in " + profile.displayName() + " and needs confirmation.\n"
                        + "Pass --yes to reset without asking.");
                return 2;
            }
            String answer = console.readLine("Delete every resource in %s at %s? [y/N] ",
                    profile.displayName(), effectiveEndpoint);
            if (answer == null || !(answer.trim().equalsIgnoreCase("y") || answer.trim().equalsIgnoreCase("yes"))) {
                printer.println("Reset cancelled.");
                return 1;
            }
        }

        try {
            new FlociHttpClient(effectiveEndpoint, profile).resetState();
        } catch (FlociException e) {
            if (e.getMessage().contains("HTTP 404") || e.getMessage().contains("HTTP 501")) {
                printer.error("Resetting state is not available on this " + profile.displayName() + " server version.\n"
                        + "Restart it without --persist for a clean state, or track progress: " + profile.serverRepo() + "/issues");
            } else {
                String message = "Failed to reset " + profile.displayName() + ": " + e.getMessage();
                // Connection errors already say what to do; others get the status hint.
                printer.error(message.contains("\n") ? message
                        : message + "\nRun '" + profile.commandPrefix() + " status" + global.instanceSelector() + "' to check the emulator.");
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
        printer.println(Ansi.green("✓") + " " + profile.displayName() + " state reset" + Ansi.gray("  (" + effectiveEndpoint + ")"));
        return 0;
    }
}
