package io.floci.cli.commands.oci.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotDeleteCommand;
import picocli.CommandLine.Command;

@Command(
        name = "delete",
        description = "Delete a Floci OCI snapshot",
        mixinStandardHelpOptions = true
)
public class OciSnapshotDeleteCommand extends SnapshotDeleteCommand {

    public OciSnapshotDeleteCommand() {
        super(ProductProfile.OCI);
    }
}
