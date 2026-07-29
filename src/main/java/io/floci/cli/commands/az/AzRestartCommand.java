package io.floci.cli.commands.az;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.RestartCommand;
import picocli.CommandLine.Command;

@Command(
        name = "restart",
        description = "Stop and restart the Floci Azure container",
        mixinStandardHelpOptions = true
)
public class AzRestartCommand extends RestartCommand {

    public AzRestartCommand() {
        super(ProductProfile.AZ);
    }
}
