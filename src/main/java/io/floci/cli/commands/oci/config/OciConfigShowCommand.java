package io.floci.cli.commands.oci.config;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.config.ConfigShowCommand;
import picocli.CommandLine.Command;

@Command(
        name = "show",
        description = "Show the active Floci OCI configuration",
        mixinStandardHelpOptions = true
)
public class OciConfigShowCommand extends ConfigShowCommand {

    public OciConfigShowCommand() {
        super(ProductProfile.OCI);
    }
}
