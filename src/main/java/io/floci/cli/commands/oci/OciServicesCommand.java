package io.floci.cli.commands.oci;

import io.floci.cli.OciGlobalOptions;
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
        description = "List services available in the running Floci OCI instance",
        mixinStandardHelpOptions = true
)
public class OciServicesCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        FlociHttpClient client = new FlociHttpClient(global.endpoint, OciGlobalOptions.CONTROL_PREFIX);

        List<String> services;
        try {
            var health = client.health();
            services = Arrays.asList(health.services());
        } catch (Exception e) {
            printer.error("Could not reach Floci OCI at " + global.endpoint + ".\nIs Floci OCI running? Try 'floci oci start'.");
            return 1;
        }

        if (printer.format() != OutputFormat.text) {
            printer.structured(Map.of("services", services, "count", services.size()));
            return 0;
        }

        printer.println(Ansi.bold("Floci OCI Services") + "  " + Ansi.gray("(" + services.size() + " enabled)"));
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
