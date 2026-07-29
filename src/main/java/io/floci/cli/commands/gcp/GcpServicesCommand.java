package io.floci.cli.commands.gcp;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.ServicesCommand;
import picocli.CommandLine.Command;

@Command(
        name = "services",
        description = "List services available in the running Floci GCP instance",
        mixinStandardHelpOptions = true
)
public class GcpServicesCommand extends ServicesCommand {

    public GcpServicesCommand() {
        super(ProductProfile.GCP);
    }
}
