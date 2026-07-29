package io.floci.cli.commands.oci;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.LogsCommand;
import picocli.CommandLine.Command;

@Command(
        name = "logs",
        description = "Fetch logs from the Floci OCI container",
        mixinStandardHelpOptions = true
)
public class OciLogsCommand extends LogsCommand {

    public OciLogsCommand() {
        super(ProductProfile.OCI);
    }
}
