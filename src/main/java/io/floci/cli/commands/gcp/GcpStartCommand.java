package io.floci.cli.commands.gcp;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StartCommand;
import picocli.CommandLine.Command;

@Command(
        name = "start",
        description = "Start the Floci GCP emulator container",
        mixinStandardHelpOptions = true
)
public class GcpStartCommand extends StartCommand {

    public GcpStartCommand() {
        super(ProductProfile.GCP);
    }
}
