package io.floci.cli.commands.gcp;

import io.floci.cli.commands.CompletionCommand;
import io.floci.cli.commands.gcp.config.GcpConfigCommand;
import io.floci.cli.commands.gcp.snapshot.GcpSnapshotCommand;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
        name = "gcp",
        description = "Manage the Floci GCP emulator (floci-gcp)%n%n" +
                "  floci gcp start   — launch the container%n" +
                "  floci gcp stop    — stop the container%n" +
                "  floci gcp status  — show health and version%n" +
                "  floci gcp doctor  — diagnose environment issues%n" +
                "  floci gcp env     — print GCP environment variables%n",
        mixinStandardHelpOptions = true,
        subcommands = {
                GcpStartCommand.class,
                GcpStopCommand.class,
                GcpRestartCommand.class,
                GcpStatusCommand.class,
                GcpLogsCommand.class,
                GcpWaitCommand.class,
                GcpVersionCommand.class,
                GcpServicesCommand.class,
                GcpResetCommand.class,
                GcpDoctorCommand.class,
                GcpEnvCommand.class,
                GcpConfigCommand.class,
                GcpSnapshotCommand.class,
                CompletionCommand.class,
                HelpCommand.class
        }
)
public class GcpCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
