package io.floci.cli.commands.gcp;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StopCommand;
import picocli.CommandLine.Command;

@Command(
        name = "stop",
        description = "Stop the Floci GCP container",
        mixinStandardHelpOptions = true
)
public class GcpStopCommand extends StopCommand {

    public GcpStopCommand() {
        super(ProductProfile.GCP);
    }
}
