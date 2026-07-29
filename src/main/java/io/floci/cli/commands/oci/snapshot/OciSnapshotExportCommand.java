package io.floci.cli.commands.oci.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotExportCommand;
import picocli.CommandLine.Command;

@Command(
        name = "export",
        description = "Export a Floci OCI snapshot to a tarball file",
        mixinStandardHelpOptions = true
)
public class OciSnapshotExportCommand extends SnapshotExportCommand {

    public OciSnapshotExportCommand() {
        super(ProductProfile.OCI);
    }
}
