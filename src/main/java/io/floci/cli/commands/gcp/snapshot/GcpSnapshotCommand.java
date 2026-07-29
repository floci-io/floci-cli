package io.floci.cli.commands.gcp.snapshot;

import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
        name = "snapshot",
        description = "Manage Floci GCP state snapshots",
        mixinStandardHelpOptions = true,
        subcommands = {
                GcpSnapshotSaveCommand.class,
                GcpSnapshotLoadCommand.class,
                GcpSnapshotListCommand.class,
                GcpSnapshotDeleteCommand.class,
                GcpSnapshotExportCommand.class,
                GcpSnapshotImportCommand.class,
                HelpCommand.class
        }
)
public class GcpSnapshotCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
