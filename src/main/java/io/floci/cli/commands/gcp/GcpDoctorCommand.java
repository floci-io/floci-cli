package io.floci.cli.commands.gcp;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.DoctorCommand;
import picocli.CommandLine.Command;

import java.util.List;

@Command(
        name = "doctor",
        description = "Run environment diagnostics for Floci GCP",
        mixinStandardHelpOptions = true
)
public class GcpDoctorCommand extends DoctorCommand {

    public GcpDoctorCommand() {
        super(ProductProfile.GCP, List.of());
    }
}
