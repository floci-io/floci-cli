package io.floci.cli.commands.aws;

import io.floci.cli.commands.*;
import io.floci.cli.commands.config.ConfigCommand;
import io.floci.cli.commands.snapshot.SnapshotCommand;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
        name = "aws",
        description = "Manage the Floci AWS emulator%n%n" +
                "  floci aws start   — launch the container%n" +
                "  floci aws stop    — stop the container%n" +
                "  floci aws status  — show health and version%n" +
                "  floci aws doctor  — diagnose environment issues%n" +
                "  floci aws env     — print AWS environment variables%n",
        mixinStandardHelpOptions = true,
        subcommands = {
                StartCommand.class,
                StopCommand.class,
                RestartCommand.class,
                StatusCommand.class,
                LogsCommand.class,
                WaitCommand.class,
                VersionCommand.class,
                ServicesCommand.class,
                ResetCommand.class,
                DoctorCommand.class,
                EnvCommand.class,
                ConfigCommand.class,
                SnapshotCommand.class,
                CompletionCommand.class,
                HelpCommand.class
        }
)
public class AwsCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
