package io.floci.cli.commands.gcp;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.VersionCommand;
import picocli.CommandLine.Command;

@Command(
        name = "version",
        description = "Show CLI version, connected Floci GCP server version, and image digest",
        mixinStandardHelpOptions = true
)
public class GcpVersionCommand extends VersionCommand {

    public GcpVersionCommand() {
        super(ProductProfile.GCP);
    }
}
