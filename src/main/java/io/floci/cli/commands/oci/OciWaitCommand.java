package io.floci.cli.commands.oci;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.WaitCommand;
import picocli.CommandLine.Command;

@Command(
        name = "wait",
        description = "Wait until Floci OCI is ready to accept requests",
        mixinStandardHelpOptions = true
)
public class OciWaitCommand extends WaitCommand {

    public OciWaitCommand() {
        super(ProductProfile.OCI);
    }
}
