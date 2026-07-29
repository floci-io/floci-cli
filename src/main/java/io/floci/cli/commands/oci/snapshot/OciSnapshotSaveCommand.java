package io.floci.cli.commands.oci.snapshot;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.output.Ansi;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

@Command(
        name = "save",
        description = "Save the current Floci OCI state as a named snapshot",
        mixinStandardHelpOptions = true
)
public class OciSnapshotSaveCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Parameters(index = "0", description = "Snapshot name", paramLabel = "<name>")
    String name;

    @Option(names = {"--message", "-m"}, description = "Optional description", paramLabel = "<text>")
    String message;

    @Override
    public Integer call() {
        global.printer().println(Ansi.yellow("Snapshots are not yet available for Floci OCI.") +
                "\nTrack progress: https://github.com/floci-io/floci-oci/issues");
        return 0;
    }
}
