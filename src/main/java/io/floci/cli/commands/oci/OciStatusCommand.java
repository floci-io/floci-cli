package io.floci.cli.commands.oci;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StatusCommand;
import picocli.CommandLine.Command;

@Command(
        name = "status",
        description = "Show Floci OCI container and server status",
        mixinStandardHelpOptions = true
)
public class OciStatusCommand extends StatusCommand {

    public OciStatusCommand() {
        super(ProductProfile.OCI);
    }
}
