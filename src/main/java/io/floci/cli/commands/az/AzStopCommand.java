package io.floci.cli.commands.az;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StopCommand;
import picocli.CommandLine.Command;

@Command(
        name = "stop",
        description = "Stop the Floci Azure container",
        mixinStandardHelpOptions = true
)
public class AzStopCommand extends StopCommand {

    public AzStopCommand() {
        super(ProductProfile.AZ);
    }
}
