package io.floci.cli.commands.az;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StatusCommand;
import picocli.CommandLine.Command;

@Command(
        name = "status",
        description = "Show Floci Azure container and server status",
        mixinStandardHelpOptions = true
)
public class AzStatusCommand extends StatusCommand {

    public AzStatusCommand() {
        super(ProductProfile.AZ);
    }
}
