package io.floci.cli.commands.az.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotImportCommand;
import picocli.CommandLine.Command;

@Command(
        name = "import",
        description = "Import a Floci Azure snapshot from a tarball file",
        mixinStandardHelpOptions = true
)
public class AzSnapshotImportCommand extends SnapshotImportCommand {

    public AzSnapshotImportCommand() {
        super(ProductProfile.AZ);
    }
}
