package io.floci.cli.commands.oci.config;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.config.ConfigProfileCommand;
import picocli.CommandLine.Command;

@Command(
        name = "profile",
        description = "Manage Floci OCI configuration profiles",
        mixinStandardHelpOptions = true
)
public class OciConfigProfileCommand extends ConfigProfileCommand {

    public OciConfigProfileCommand() {
        super(ProductProfile.OCI);
    }
}
