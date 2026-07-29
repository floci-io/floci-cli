package io.floci.cli.commands.oci;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

@Command(
        name = "logs",
        description = "Fetch logs from the Floci OCI container",
        mixinStandardHelpOptions = true
)
public class OciLogsCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Option(names = {"--follow", "-f"}, description = "Follow log output")
    boolean follow;

    @Option(names = {"--tail"}, description = "Number of lines from the end (0 = all)", defaultValue = "100", paramLabel = "<n>")
    int tail;

    @Option(names = {"--since"}, description = "Show logs since duration (e.g. 5m, 1h) or timestamp", paramLabel = "<duration>")
    String since;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        DockerClient docker = new DockerClient();

        try {
            var info = docker.inspectContainer(global.container);
            if (info.isEmpty()) {
                printer.error("Container '" + global.container + "' not found.\nRun 'floci oci start' to launch one.");
                return 1;
            }
        } catch (DockerException e) {
            printer.error("Failed to inspect container: " + e.getMessage());
            return 1;
        }

        try {
            docker.streamLogs(global.container, follow, tail, since);
            return 0;
        } catch (DockerException e) {
            printer.error("Failed to fetch logs: " + e.getMessage());
            return 1;
        }
    }
}