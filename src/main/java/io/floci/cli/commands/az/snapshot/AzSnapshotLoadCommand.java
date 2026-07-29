package io.floci.cli.commands.az.snapshot;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.snapshot.SnapshotLoadCommand;
import picocli.CommandLine.Command;

@Command(
        name = "load",
        description = "Load a Floci Azure snapshot",
        mixinStandardHelpOptions = true
)
public class AzSnapshotLoadCommand extends SnapshotLoadCommand {

    public AzSnapshotLoadCommand() {
        super(ProductProfile.AZ);
    }
}
