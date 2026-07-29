package io.floci.cli.commands.oci.snapshot;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.output.Ansi;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

@Command(
        name = "export",
        description = "Export a Floci OCI snapshot to a tarball file",
        mixinStandardHelpOptions = true
)
public class OciSnapshotExportCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Parameters(index = "0", description = "Snapshot name", paramLabel = "<name>")
    String name;

    @Option(names = {"--file", "-f"}, description = "Destination file path", required = true, paramLabel = "<path>")
    String outputFile;

    @Override
    public Integer call() {
        global.printer().println(Ansi.yellow("Snapshots are not yet available for Floci OCI.") +
                "\nTrack progress: https://github.com/floci-io/floci-oci/issues");
        return 0;
    }
}
