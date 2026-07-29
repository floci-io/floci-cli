package io.floci.cli.commands.gcp;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.WaitCommand;
import picocli.CommandLine.Command;

@Command(
        name = "wait",
        description = "Wait until Floci GCP is ready to accept requests",
        mixinStandardHelpOptions = true
)
public class GcpWaitCommand extends WaitCommand {

    public GcpWaitCommand() {
        super(ProductProfile.GCP);
    }
}
