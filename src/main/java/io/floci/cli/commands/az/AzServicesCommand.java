package io.floci.cli.commands.az;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.ServicesCommand;
import picocli.CommandLine.Command;

@Command(
        name = "services",
        description = "List services available in the running Floci Azure instance",
        mixinStandardHelpOptions = true
)
public class AzServicesCommand extends ServicesCommand {

    public AzServicesCommand() {
        super(ProductProfile.AZ);
    }
}
