package io.floci.cli.commands.oci;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StartCommand;
import picocli.CommandLine.Command;

@Command(
        name = "start",
        description = "Start the Floci OCI emulator container",
        mixinStandardHelpOptions = true
)
public class OciStartCommand extends StartCommand {

    public OciStartCommand() {
        super(ProductProfile.OCI);
    }
}
