package io.floci.cli.commands.az.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotSaveCommand;
import picocli.CommandLine.Command;

@Command(
        name = "save",
        description = "Save the current Floci Azure state as a named snapshot",
        mixinStandardHelpOptions = true
)
public class AzSnapshotSaveCommand extends SnapshotSaveCommand {

    public AzSnapshotSaveCommand() {
        super(ProductProfile.AZ);
    }
}
