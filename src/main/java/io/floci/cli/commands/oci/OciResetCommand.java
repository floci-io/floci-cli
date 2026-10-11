package io.floci.cli.commands.oci;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.ResetCommand;
import picocli.CommandLine.Command;

@Command(
        name = "reset",
        description = "Delete every resource in the running Floci OCI instance, keeping it running",
        mixinStandardHelpOptions = true
)
public class OciResetCommand extends ResetCommand {

    public OciResetCommand() {
        super(ProductProfile.OCI);
    }
}
