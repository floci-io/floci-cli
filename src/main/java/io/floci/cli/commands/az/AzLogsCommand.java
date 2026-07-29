package io.floci.cli.commands.az;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.LogsCommand;
import picocli.CommandLine.Command;

@Command(
        name = "logs",
        description = "Fetch logs from the Floci Azure container",
        mixinStandardHelpOptions = true
)
public class AzLogsCommand extends LogsCommand {

    public AzLogsCommand() {
        super(ProductProfile.AZ);
    }
}
