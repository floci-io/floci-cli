package io.floci.cli.commands.oci.snapshot;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "list",
        description = "List available Floci OCI snapshots",
        mixinStandardHelpOptions = true
)
public class OciSnapshotListCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        if (printer.format() != OutputFormat.text) {
            printer.structured(Map.of("snapshots", List.of(), "note", "not yet available"));
            return 0;
        }
        printer.println(Ansi.gray("No snapshots found.") +
                " (Snapshot support for Floci OCI is coming — https://github.com/floci-io/floci-oci/issues)");
        return 0;
    }
}
