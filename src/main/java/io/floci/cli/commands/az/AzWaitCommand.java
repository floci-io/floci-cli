package io.floci.cli.commands.az;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.WaitCommand;
import picocli.CommandLine.Command;

@Command(
        name = "wait",
        description = "Wait until Floci Azure is ready to accept requests",
        mixinStandardHelpOptions = true
)
public class AzWaitCommand extends WaitCommand {

    public AzWaitCommand() {
        super(ProductProfile.AZ);
    }
}
