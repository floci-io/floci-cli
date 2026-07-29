package io.floci.cli.commands.gcp.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotListCommand;
import picocli.CommandLine.Command;

@Command(
        name = "list",
        description = "List available Floci GCP snapshots",
        mixinStandardHelpOptions = true
)
public class GcpSnapshotListCommand extends SnapshotListCommand {

    public GcpSnapshotListCommand() {
        super(ProductProfile.GCP);
    }
}
