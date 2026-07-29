package io.floci.cli.commands.oci.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotLoadCommand;
import picocli.CommandLine.Command;

@Command(
        name = "load",
        description = "Load a Floci OCI snapshot",
        mixinStandardHelpOptions = true
)
public class OciSnapshotLoadCommand extends SnapshotLoadCommand {

    public OciSnapshotLoadCommand() {
        super(ProductProfile.OCI);
    }
}
