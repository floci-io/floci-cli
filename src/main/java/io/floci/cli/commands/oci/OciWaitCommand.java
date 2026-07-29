package io.floci.cli.commands.oci;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.commands.WaitCommand;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "wait",
        description = "Wait until Floci OCI is ready to accept requests",
        mixinStandardHelpOptions = true
)
public class OciWaitCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Option(names = {"--timeout"}, description = "Maximum time to wait (e.g. 30s, 2m)", defaultValue = "30s", paramLabel = "<duration>")
    String timeout;

    @Option(names = {"--service"}, description = "Wait until a specific service is enabled", paramLabel = "<name>")
    String service;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        long timeoutMillis = WaitCommand.parseDuration(timeout);
        String effectiveEndpoint = global.resolvedEndpoint(new io.floci.cli.docker.DockerClient());
        FlociHttpClient client = new FlociHttpClient(effectiveEndpoint, OciGlobalOptions.CONTROL_PREFIX);
        Instant deadline = Instant.now().plusMillis(timeoutMillis);

        while (Instant.now().isBefore(deadline)) {
            if (isReady(client, service)) {
                if (printer.format() != OutputFormat.text) {
                    printer.structured(Map.of("ready", true, "endpoint", effectiveEndpoint));
                } else {
                    printer.println(Ansi.green("Floci OCI is ready") + " (" + effectiveEndpoint + ")");
                }
                return 0;
            }
            long remaining = Duration.between(Instant.now(), deadline).toSeconds();
            printer.print("\r" + Ansi.gray("Waiting... (" + remaining + "s remaining)") + "   ");
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        printer.error("Timed out waiting for Floci OCI after " + timeout + ".\nIs the container running? Try 'floci oci status'.");
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
}