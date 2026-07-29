package io.floci.cli.commands.gcp;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StatusCommand;
import picocli.CommandLine.Command;

@Command(
        name = "status",
        description = "Show Floci GCP container and server status",
        mixinStandardHelpOptions = true
)
public class GcpStatusCommand extends StatusCommand {

    public GcpStatusCommand() {
        super(ProductProfile.GCP);
    }
}
