package io.floci.cli.commands.az.snapshot;

import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
        name = "snapshot",
        description = "Manage Floci Azure state snapshots",
        mixinStandardHelpOptions = true,
        subcommands = {
                AzSnapshotSaveCommand.class,
                AzSnapshotLoadCommand.class,
                AzSnapshotListCommand.class,
                AzSnapshotDeleteCommand.class,
                AzSnapshotExportCommand.class,
                AzSnapshotImportCommand.class,
                HelpCommand.class
        }
)
public class AzSnapshotCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
