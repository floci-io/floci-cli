package io.floci.cli.commands.oci;

import io.floci.cli.commands.CompletionCommand;
import io.floci.cli.commands.oci.config.OciConfigCommand;
import io.floci.cli.commands.oci.snapshot.OciSnapshotCommand;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
        name = "oci",
        description = "Manage the Floci OCI emulator (floci-oci)%n%n" +
                "  floci oci start   — launch the container%n" +
                "  floci oci stop    — stop the container%n" +
                "  floci oci status  — show health and version%n" +
                "  floci oci doctor  — diagnose environment issues%n" +
                "  floci oci env     — print OCI environment variables%n" +
                "  floci oci setup   — create a local OCI CLI profile (key + ~/.oci/config)%n",
        mixinStandardHelpOptions = true,
        subcommands = {
                OciStartCommand.class,
                OciStopCommand.class,
                OciRestartCommand.class,
                OciStatusCommand.class,
                OciLogsCommand.class,
                OciWaitCommand.class,
                OciVersionCommand.class,
                OciServicesCommand.class,
                OciDoctorCommand.class,
                OciEnvCommand.class,
                OciSetupCommand.class,
                OciConfigCommand.class,
                OciSnapshotCommand.class,
                CompletionCommand.class,
                HelpCommand.class
        }
)
public class OciCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
