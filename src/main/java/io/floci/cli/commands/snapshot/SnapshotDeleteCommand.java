package io.floci.cli.commands.snapshot;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.http.FlociException;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

@Command(
        name = "delete",
        description = "Delete a Floci AWS snapshot",
        mixinStandardHelpOptions = true
)
public class SnapshotDeleteCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public SnapshotDeleteCommand() {
        this(ProductProfile.AWS);
    }

    protected SnapshotDeleteCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Parameters(index = "0", description = "Snapshot name", paramLabel = "<name>")
    String name;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        // Resolve the endpoint from the container's port mapping, like status/services/wait do,
        // so a container started with --port <n> is still found without --endpoint.
        String effectiveEndpoint = global.resolvedEndpoint(new DockerClient());
        FlociHttpClient client = new FlociHttpClient(effectiveEndpoint, profile.controlPrefix());
        try {
            client.deleteSnapshot(name);
            printer.println(Ansi.green("Snapshot deleted:") + " " + name);
            return 0;
        } catch (FlociException e) {
            printer.error("Failed to delete snapshot: " + e.getMessage());
            return 1;
        }
    }
}
