package io.floci.cli.commands.gcp.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotImportCommand;
import picocli.CommandLine.Command;

@Command(
        name = "import",
        description = "Import a Floci GCP snapshot from a tarball file",
        mixinStandardHelpOptions = true
)
public class GcpSnapshotImportCommand extends SnapshotImportCommand {

    public GcpSnapshotImportCommand() {
        super(ProductProfile.GCP);
    }
}
