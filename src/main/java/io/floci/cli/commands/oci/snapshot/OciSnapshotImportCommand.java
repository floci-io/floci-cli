package io.floci.cli.commands.oci.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotImportCommand;
import picocli.CommandLine.Command;

@Command(
        name = "import",
        description = "Import a Floci OCI snapshot from a tarball file",
        mixinStandardHelpOptions = true
)
public class OciSnapshotImportCommand extends SnapshotImportCommand {

    public OciSnapshotImportCommand() {
        super(ProductProfile.OCI);
    }
}
