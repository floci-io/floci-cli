package io.floci.cli.commands.oci.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotSaveCommand;
import picocli.CommandLine.Command;

@Command(
        name = "save",
        description = "Save the current Floci OCI state as a named snapshot",
        mixinStandardHelpOptions = true
)
public class OciSnapshotSaveCommand extends SnapshotSaveCommand {

    public OciSnapshotSaveCommand() {
        super(ProductProfile.OCI);
    }
}
