package io.floci.cli.commands.az.config;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.config.ConfigValidateCommand;
import picocli.CommandLine.Command;

@Command(
        name = "validate",
        description = "Validate a docker-compose.yml file for Floci Azure compatibility",
        mixinStandardHelpOptions = true
)
public class AzConfigValidateCommand extends ConfigValidateCommand {

    public AzConfigValidateCommand() {
        super(ProductProfile.AZ, false, "Azure Functions");
    }
}
