package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.util.Durations;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;

import java.util.Map;
import picocli.CommandLine.*;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;

@Command(
        name = "wait",
        description = "Wait until Floci AWS is ready to accept requests",
        mixinStandardHelpOptions = true
)
public class WaitCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public WaitCommand() {
        this(ProductProfile.AWS);
    }

    protected WaitCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Option(names = {"--timeout"}, description = "Maximum time to wait (e.g. 30s, 2m)", defaultValue = "30s", paramLabel = "<duration>")
    String timeout;

    @Option(names = {"--service"}, description = "Wait until a specific service is enabled", paramLabel = "<name>")
    String service;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        long timeoutMillis = Durations.parseDuration(timeout);
        String effectiveEndpoint = global.resolvedEndpoint(new io.floci.cli.docker.DockerClient());
        FlociHttpClient client = new FlociHttpClient(effectiveEndpoint, profile.controlPrefix());
        Instant deadline = Instant.now().plusMillis(timeoutMillis);

        while (Instant.now().isBefore(deadline)) {
            if (isReady(client, service)) {
                if (printer.format() != OutputFormat.text) {
                    printer.structured(Map.of("ready", true, "endpoint", effectiveEndpoint));
                } else {
                    printer.println(Ansi.green(profile.displayName() + " is ready") + " (" + effectiveEndpoint + ")");
                }
                return 0;
            }
            printSpinner(printer, deadline);
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        printer.error("Timed out waiting for " + profile.displayName() + " after " + timeout
                + ".\nIs the container running? Try '" + profile.commandPrefix() + " status'.");
        return 1;
    }

    private boolean isReady(FlociHttpClient client, String requiredService) {
        try {
            var health = client.health();
            if (requiredService == null) return true;
            for (String s : health.services()) {
                if (s.equalsIgnoreCase(requiredService)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void printSpinner(Printer printer, Instant deadline) {
        long remaining = Duration.between(Instant.now(), deadline).toSeconds();
        printer.print("\r" + Ansi.gray("Waiting... (" + remaining + "s remaining)") + "   ");
    }

}
