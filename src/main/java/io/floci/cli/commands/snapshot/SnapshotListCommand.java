package io.floci.cli.commands.snapshot;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.http.FlociException;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

@Command(
        name = "list",
        description = "List available Floci AWS snapshots",
        mixinStandardHelpOptions = true
)
public class SnapshotListCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public SnapshotListCommand() {
        this(ProductProfile.AWS);
    }

    protected SnapshotListCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();
        FlociHttpClient client = new FlociHttpClient(global.endpoint, profile.controlPrefix());
        try {
            var node = client.listSnapshots();
            if (printer.format() != OutputFormat.text) {
                printer.structured(node);
                return 0;
            }
            if (node.isArray() && node.size() == 0) {
                printer.println(Ansi.gray("No snapshots found."));
                return 0;
            }
            printer.println(Ansi.bold("Snapshots:"));
            node.forEach(s -> printer.println("  " + s.asText()));
            return 0;
        } catch (FlociException e) {
            if (e.getMessage().contains("404") || e.getMessage().contains("501")) {
                printer.error("Snapshot API not available on this server version.\n" +
                        "Track progress: " + profile.serverRepo() + "/issues");
            } else {
                printer.error("Failed to list snapshots: " + e.getMessage());
            }
            return 1;
        }
    }
}
