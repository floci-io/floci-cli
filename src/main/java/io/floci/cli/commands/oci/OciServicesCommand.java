package io.floci.cli.commands.oci;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.ServicesCommand;
import picocli.CommandLine.Command;

@Command(
        name = "services",
        description = "List services available in the running Floci OCI instance",
        mixinStandardHelpOptions = true
)
public class OciServicesCommand extends ServicesCommand {

    public OciServicesCommand() {
        super(ProductProfile.OCI);
    }
}
