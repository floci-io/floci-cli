package io.floci.cli.commands.oci;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StopCommand;
import picocli.CommandLine.Command;

@Command(
        name = "stop",
        description = "Stop the Floci OCI container",
        mixinStandardHelpOptions = true
)
public class OciStopCommand extends StopCommand {

    public OciStopCommand() {
        super(ProductProfile.OCI);
    }
}
