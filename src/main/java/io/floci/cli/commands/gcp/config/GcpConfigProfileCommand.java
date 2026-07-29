package io.floci.cli.commands.gcp.config;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.config.ConfigProfileCommand;
import picocli.CommandLine.Command;

@Command(
        name = "profile",
        description = "Manage Floci GCP configuration profiles",
        mixinStandardHelpOptions = true
)
public class GcpConfigProfileCommand extends ConfigProfileCommand {

    public GcpConfigProfileCommand() {
        super(ProductProfile.GCP);
    }
}
