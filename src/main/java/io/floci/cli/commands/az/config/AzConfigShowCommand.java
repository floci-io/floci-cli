package io.floci.cli.commands.az.config;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.config.ConfigShowCommand;
import picocli.CommandLine.Command;

@Command(
        name = "show",
        description = "Show the active Floci Azure configuration",
        mixinStandardHelpOptions = true
)
public class AzConfigShowCommand extends ConfigShowCommand {

    public AzConfigShowCommand() {
        super(ProductProfile.AZ);
    }
}
