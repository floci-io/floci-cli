package io.floci.cli.commands.gcp;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.LogsCommand;
import picocli.CommandLine.Command;

@Command(
        name = "logs",
        description = "Fetch logs from the Floci GCP container",
        mixinStandardHelpOptions = true
)
public class GcpLogsCommand extends LogsCommand {

    public GcpLogsCommand() {
        super(ProductProfile.GCP);
    }
}
