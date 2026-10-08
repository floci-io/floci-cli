package io.floci.cli.commands.az;

import io.floci.cli.commands.CompletionCommand;
import io.floci.cli.commands.az.config.AzConfigCommand;
import io.floci.cli.commands.az.snapshot.AzSnapshotCommand;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
        name = "az",
        description = "Manage the Floci Azure emulator (floci-az)%n%n" +
                "  floci az start   — launch the container%n" +
                "  floci az stop    — stop the container%n" +
                "  floci az status  — show health and version%n" +
                "  floci az doctor  — diagnose environment issues%n" +
                "  floci az env     — print Azure environment variables%n" +
                "  floci az setup   — point the az CLI at the emulator (cert, cloud, login)%n",
        mixinStandardHelpOptions = true,
        subcommands = {
                AzStartCommand.class,
                AzStopCommand.class,
                AzRestartCommand.class,
                AzStatusCommand.class,
                AzLogsCommand.class,
                AzWaitCommand.class,
                AzVersionCommand.class,
                AzServicesCommand.class,
                AzDoctorCommand.class,
                AzEnvCommand.class,
                AzSetupCommand.class,
                AzConfigCommand.class,
                AzSnapshotCommand.class,
                CompletionCommand.class,
                HelpCommand.class
        }
)
public class AzCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
