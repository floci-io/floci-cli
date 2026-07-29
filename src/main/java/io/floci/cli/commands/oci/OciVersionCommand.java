package io.floci.cli.commands.oci;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.VersionCommand;
import picocli.CommandLine.Command;

@Command(
        name = "version",
        description = "Show CLI version, connected Floci OCI server version, and image digest",
        mixinStandardHelpOptions = true
)
public class OciVersionCommand extends VersionCommand {

    public OciVersionCommand() {
        super(ProductProfile.OCI);
    }
}
