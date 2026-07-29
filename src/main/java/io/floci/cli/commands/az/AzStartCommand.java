package io.floci.cli.commands.az;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StartCommand;
import picocli.CommandLine.Command;

@Command(
        name = "start",
        description = "Start the Floci Azure emulator container",
        mixinStandardHelpOptions = true
)
public class AzStartCommand extends StartCommand {

    public AzStartCommand() {
        super(ProductProfile.AZ);
    }
}
