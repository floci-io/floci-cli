package io.floci.cli.commands.az;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.DoctorCommand;
import picocli.CommandLine.Command;

import java.util.List;

@Command(
        name = "doctor",
        description = "Run environment diagnostics for Floci Azure",
        mixinStandardHelpOptions = true
)
public class AzDoctorCommand extends DoctorCommand {

    public AzDoctorCommand() {
        super(ProductProfile.AZ, AZ_COMPANION_CHECKS);
    }
}
