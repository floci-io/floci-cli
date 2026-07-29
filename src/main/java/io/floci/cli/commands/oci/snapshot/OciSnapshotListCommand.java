package io.floci.cli.commands.oci.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotListCommand;
import picocli.CommandLine.Command;

@Command(
        name = "list",
        description = "List available Floci OCI snapshots",
        mixinStandardHelpOptions = true
)
public class OciSnapshotListCommand extends SnapshotListCommand {

    public OciSnapshotListCommand() {
        super(ProductProfile.OCI);
    }
}
