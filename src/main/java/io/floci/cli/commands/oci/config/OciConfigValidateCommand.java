package io.floci.cli.commands.oci.config;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.config.ConfigValidateCommand;
import picocli.CommandLine.Command;

@Command(
        name = "validate",
        description = "Validate a docker-compose.yml file for Floci OCI compatibility",
        mixinStandardHelpOptions = true
)
public class OciConfigValidateCommand extends ConfigValidateCommand {

    public OciConfigValidateCommand() {
        super(ProductProfile.OCI, false, "Functions (Fn Project sidecar)");
    }
}
