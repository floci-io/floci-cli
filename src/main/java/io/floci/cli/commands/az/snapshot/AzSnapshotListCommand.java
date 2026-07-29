package io.floci.cli.commands.az.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotListCommand;
import picocli.CommandLine.Command;

@Command(
        name = "list",
        description = "List available Floci Azure snapshots",
        mixinStandardHelpOptions = true
)
public class AzSnapshotListCommand extends SnapshotListCommand {

    public AzSnapshotListCommand() {
        super(ProductProfile.AZ);
    }
}
