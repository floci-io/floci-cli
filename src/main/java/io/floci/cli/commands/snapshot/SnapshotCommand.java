package io.floci.cli.commands.snapshot;

import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
        name = "snapshot",
        description = "Manage Floci AWS state snapshots",
        mixinStandardHelpOptions = true,
        subcommands = {
                SnapshotSaveCommand.class,
                SnapshotLoadCommand.class,
                SnapshotListCommand.class,
                SnapshotDeleteCommand.class,
                SnapshotExportCommand.class,
                SnapshotImportCommand.class,
                HelpCommand.class
        }
)
public class SnapshotCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
