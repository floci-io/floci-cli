package io.floci.cli.commands.az;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.VersionCommand;
import picocli.CommandLine.Command;

@Command(
        name = "version",
        description = "Show CLI version, connected Floci Azure server version, and image digest",
        mixinStandardHelpOptions = true
)
public class AzVersionCommand extends VersionCommand {

    public AzVersionCommand() {
        super(ProductProfile.AZ);
    }
}
