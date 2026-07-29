package io.floci.cli.commands.gcp.config;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.config.ConfigValidateCommand;
import picocli.CommandLine.Command;

@Command(
        name = "validate",
        description = "Validate a docker-compose.yml file for Floci GCP compatibility",
        mixinStandardHelpOptions = true
)
public class GcpConfigValidateCommand extends ConfigValidateCommand {

    public GcpConfigValidateCommand() {
        super(ProductProfile.GCP, false, "Kafka/Redpanda support");
    }
}
