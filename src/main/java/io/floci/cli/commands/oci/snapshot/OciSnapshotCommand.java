package io.floci.cli.commands.oci.snapshot;

import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
        name = "snapshot",
        description = "Manage Floci OCI state snapshots (coming soon)",
        mixinStandardHelpOptions = true,
        subcommands = {
                OciSnapshotSaveCommand.class,
                OciSnapshotLoadCommand.class,
                OciSnapshotListCommand.class,
                OciSnapshotDeleteCommand.class,
                OciSnapshotExportCommand.class,
                OciSnapshotImportCommand.class,
                HelpCommand.class
        }
)
public class OciSnapshotCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
