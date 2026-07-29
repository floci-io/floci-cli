package io.floci.cli.commands.gcp.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotDeleteCommand;
import picocli.CommandLine.Command;

@Command(
        name = "delete",
        description = "Delete a Floci GCP snapshot",
        mixinStandardHelpOptions = true
)
public class GcpSnapshotDeleteCommand extends SnapshotDeleteCommand {

    public GcpSnapshotDeleteCommand() {
        super(ProductProfile.GCP);
    }
}
