package io.floci.cli.commands.snapshot;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

@Command(
        name = "import",
        description = "Import a Floci AWS snapshot from a tarball file",
        mixinStandardHelpOptions = true
)
public class SnapshotImportCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public SnapshotImportCommand() {
        this(ProductProfile.AWS);
    }

    protected SnapshotImportCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Option(names = {"--file", "-f"}, description = "Source tarball path", required = true, paramLabel = "<path>")
    String inputFile;

    @Option(names = {"--name"}, description = "Name for the imported snapshot", paramLabel = "<name>")
    String name;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        // TODO: implement POST /_floci/snapshots/import with multipart upload
        printer.error("snapshot import requires floci-server >= 1.6.0\n" +
                "Track progress: " + profile.serverRepo() + "/issues");
        return 1;
    }
}
