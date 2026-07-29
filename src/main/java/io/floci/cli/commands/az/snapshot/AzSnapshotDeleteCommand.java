package io.floci.cli.commands.az.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotDeleteCommand;
import picocli.CommandLine.Command;

@Command(
        name = "delete",
        description = "Delete a Floci Azure snapshot",
        mixinStandardHelpOptions = true
)
public class AzSnapshotDeleteCommand extends SnapshotDeleteCommand {

    public AzSnapshotDeleteCommand() {
        super(ProductProfile.AZ);
    }
}
