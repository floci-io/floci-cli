package io.floci.cli.commands.az;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.ResetCommand;
import picocli.CommandLine.Command;

@Command(
        name = "reset",
        description = "Delete every resource in the running Floci Azure instance, keeping it running",
        mixinStandardHelpOptions = true
)
public class AzResetCommand extends ResetCommand {

    public AzResetCommand() {
        super(ProductProfile.AZ);
    }
}
