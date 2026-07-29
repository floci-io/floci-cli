package io.floci.cli.commands.oci;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.DoctorCommand;
import picocli.CommandLine.Command;

import java.util.List;

@Command(
        name = "doctor",
        description = "Run environment diagnostics for Floci OCI",
        mixinStandardHelpOptions = true
)
public class OciDoctorCommand extends DoctorCommand {

    public OciDoctorCommand() {
        super(ProductProfile.OCI, List.of());
    }
}
