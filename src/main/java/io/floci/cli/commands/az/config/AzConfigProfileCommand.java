package io.floci.cli.commands.az.config;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.config.ConfigProfileCommand;
import picocli.CommandLine.Command;

@Command(
        name = "profile",
        description = "Manage Floci Azure configuration profiles",
        mixinStandardHelpOptions = true
)
public class AzConfigProfileCommand extends ConfigProfileCommand {

    public AzConfigProfileCommand() {
        super(ProductProfile.AZ);
    }
}
