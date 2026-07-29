package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "services",
        description = "List services available in the running Floci AWS instance",
        mixinStandardHelpOptions = true
)
public class ServicesCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public ServicesCommand() {
        this(ProductProfile.AWS);
    }

    protected ServicesCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Option(names = {"--mode"}, description = "Filter mode: docker, in-process, all (default: all)", defaultValue = "all", paramLabel = "docker|in-process|all")
    String mode;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        // Resolve the endpoint from the container's port mapping, like status/wait/env do,
        // so a container started with --port <n> is still found without --endpoint.
        String effectiveEndpoint = global.resolvedEndpoint(new DockerClient());
        FlociHttpClient client = new FlociHttpClient(effectiveEndpoint, profile.controlPrefix());

        List<String> services;
        try {
            var health = client.health();
            services = Arrays.asList(health.services());
        } catch (Exception e) {
            printer.error("Could not reach " + profile.displayName() + " at " + effectiveEndpoint
                    + ".\nIs " + profile.displayName() + " running? Try '" + profile.commandPrefix() + " start'.");
            return 1;
        }

        if (printer.format() != OutputFormat.text) {
            printer.structured(Map.of("services", services, "count", services.size()));
            return 0;
        }

        printer.println(Ansi.bold(profile.displayName() + " Services") + "  " + Ansi.gray("(" + services.size() + " enabled)"));
        printer.println("");
        for (String svc : services) {
            printer.println("  " + Ansi.green("✓") + "  " + svc);
        }
        if (services.isEmpty()) {
            printer.println("  " + Ansi.gray("(no services reported)"));
        }

        return 0;
    }
}
