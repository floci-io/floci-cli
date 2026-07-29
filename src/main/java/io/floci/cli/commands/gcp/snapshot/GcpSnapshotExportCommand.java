package io.floci.cli.commands.gcp.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotExportCommand;
import picocli.CommandLine.Command;

@Command(
        name = "export",
        description = "Export a Floci GCP snapshot to a tarball file",
        mixinStandardHelpOptions = true
)
public class GcpSnapshotExportCommand extends SnapshotExportCommand {

    public GcpSnapshotExportCommand() {
        super(ProductProfile.GCP);
    }
}
