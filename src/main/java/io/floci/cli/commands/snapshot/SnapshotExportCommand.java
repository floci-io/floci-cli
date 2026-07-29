package io.floci.cli.commands.snapshot;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

@Command(
        name = "export",
        description = "Export a Floci AWS snapshot to a tarball file",
        mixinStandardHelpOptions = true
)
public class SnapshotExportCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public SnapshotExportCommand() {
        this(ProductProfile.AWS);
    }

    protected SnapshotExportCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Parameters(index = "0", description = "Snapshot name", paramLabel = "<name>")
    String name;

    @Option(names = {"--file", "-f"}, description = "Destination file path", required = true, paramLabel = "<path>")
    String outputFile;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        // TODO: implement GET /_floci/snapshots/<name>/export streaming to file
        printer.error("snapshot export requires floci-server >= 1.6.0\n" +
                "Track progress: " + profile.serverRepo() + "/issues");
        return 1;
    }
}
