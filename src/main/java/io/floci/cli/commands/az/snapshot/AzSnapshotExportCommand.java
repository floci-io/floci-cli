package io.floci.cli.commands.az.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotExportCommand;
import picocli.CommandLine.Command;

@Command(
        name = "export",
        description = "Export a Floci Azure snapshot to a tarball file",
        mixinStandardHelpOptions = true
)
public class AzSnapshotExportCommand extends SnapshotExportCommand {

    public AzSnapshotExportCommand() {
        super(ProductProfile.AZ);
    }
}
