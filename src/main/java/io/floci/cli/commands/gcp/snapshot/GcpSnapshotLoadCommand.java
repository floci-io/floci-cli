package io.floci.cli.commands.gcp.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotLoadCommand;
import picocli.CommandLine.Command;

@Command(
        name = "load",
        description = "Load a Floci GCP snapshot",
        mixinStandardHelpOptions = true
)
public class GcpSnapshotLoadCommand extends SnapshotLoadCommand {

    public GcpSnapshotLoadCommand() {
        super(ProductProfile.GCP);
    }
}
