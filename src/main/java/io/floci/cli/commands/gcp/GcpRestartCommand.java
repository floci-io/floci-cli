package io.floci.cli.commands.gcp;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.RestartCommand;
import picocli.CommandLine.Command;

@Command(
        name = "restart",
        description = "Stop and restart the Floci GCP container",
        mixinStandardHelpOptions = true
)
public class GcpRestartCommand extends RestartCommand {

    public GcpRestartCommand() {
        super(ProductProfile.GCP);
    }
}
