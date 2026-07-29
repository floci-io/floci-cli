package io.floci.cli.commands.snapshot;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.http.FlociException;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

@Command(
        name = "load",
        description = "Load a Floci AWS snapshot",
        mixinStandardHelpOptions = true
)
public class SnapshotLoadCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public SnapshotLoadCommand() {
        this(ProductProfile.AWS);
    }

    protected SnapshotLoadCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Parameters(index = "0", description = "Snapshot name", paramLabel = "<name>")
    String name;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        FlociHttpClient client = new FlociHttpClient(global.endpoint, profile.controlPrefix());
        try {
            client.loadSnapshot(name);
            printer.println(Ansi.green("Snapshot loaded:") + " " + name);
            return 0;
        } catch (FlociException e) {
            if (e.getMessage().contains("404") || e.getMessage().contains("501")) {
                printer.error("Snapshot API not available on this server version.\n" +
                        "Track progress: " + profile.serverRepo() + "/issues");
            } else {
                printer.error("Failed to load snapshot: " + e.getMessage());
            }
            return 1;
        }
    }
}
