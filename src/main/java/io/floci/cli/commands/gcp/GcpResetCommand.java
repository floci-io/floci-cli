package io.floci.cli.commands.gcp;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.ResetCommand;
import picocli.CommandLine.Command;

@Command(
        name = "reset",
        description = "Delete every resource in the running Floci GCP instance, keeping it running",
        mixinStandardHelpOptions = true
)
public class GcpResetCommand extends ResetCommand {

    public GcpResetCommand() {
        super(ProductProfile.GCP);
    }
}
