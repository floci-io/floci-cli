package io.floci.cli.commands.gcp.config;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.config.ConfigShowCommand;
import picocli.CommandLine.Command;

@Command(
        name = "show",
        description = "Show the active Floci GCP configuration",
        mixinStandardHelpOptions = true
)
public class GcpConfigShowCommand extends ConfigShowCommand {

    public GcpConfigShowCommand() {
        super(ProductProfile.GCP);
    }
}
