package io.floci.cli.commands.oci;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.RestartCommand;
import picocli.CommandLine.Command;

@Command(
        name = "restart",
        description = "Stop and restart the Floci OCI container",
        mixinStandardHelpOptions = true
)
public class OciRestartCommand extends RestartCommand {

    public OciRestartCommand() {
        super(ProductProfile.OCI);
    }
}
