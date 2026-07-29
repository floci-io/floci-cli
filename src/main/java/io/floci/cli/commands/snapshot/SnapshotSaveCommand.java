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
        name = "save",
        description = "Save the current Floci AWS state as a named snapshot",
        mixinStandardHelpOptions = true
)
public class SnapshotSaveCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public SnapshotSaveCommand() {
        this(ProductProfile.AWS);
    }

    protected SnapshotSaveCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Parameters(index = "0", description = "Snapshot name", paramLabel = "<name>")
    String name;

    @Option(names = {"--message", "-m"}, description = "Optional description", paramLabel = "<text>")
    String message;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        FlociHttpClient client = new FlociHttpClient(global.endpoint, profile.controlPrefix());
        try {
            client.postSnapshot(name);
            printer.println(Ansi.green("Snapshot saved:") + " " + name);
            return 0;
        } catch (FlociException e) {
            if (e.getMessage().contains("404") || e.getMessage().contains("501")) {
                printer.error("Snapshot API not available on this server version.\n" +
                        "Track progress: " + profile.serverRepo() + "/issues");
            } else {
                printer.error("Failed to save snapshot: " + e.getMessage());
            }
            return 1;
        }
    }
}
