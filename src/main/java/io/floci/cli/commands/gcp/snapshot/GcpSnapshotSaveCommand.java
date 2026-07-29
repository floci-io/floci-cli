package io.floci.cli.commands.gcp.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotSaveCommand;
import picocli.CommandLine.Command;

@Command(
        name = "save",
        description = "Save the current Floci GCP state as a named snapshot",
        mixinStandardHelpOptions = true
)
public class GcpSnapshotSaveCommand extends SnapshotSaveCommand {

    public GcpSnapshotSaveCommand() {
        super(ProductProfile.GCP);
    }
}
