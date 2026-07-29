package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

@Command(
        name = "logs",
        description = "Fetch logs from the Floci AWS container",
        mixinStandardHelpOptions = true
)
public class LogsCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public LogsCommand() {
        this(ProductProfile.AWS);
    }

    protected LogsCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Option(names = {"--follow", "-f"}, description = "Follow log output")
    boolean follow;

    @Option(names = {"--tail"}, description = "Number of lines from the end (0 = all)", defaultValue = "100", paramLabel = "<n>")
    int tail;

    @Option(names = {"--since"}, description = "Show logs since duration (e.g. 5m, 1h) or timestamp", paramLabel = "<duration>")
    String since;

    @Option(names = {"--service"}, description = "Filter to a specific service (not yet supported; requires server-side filtering)", paramLabel = "<name>")
    String service;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        DockerClient docker = new DockerClient();

        try {
            var info = docker.inspectContainer(global.container);
            if (info.isEmpty()) {
                printer.error("Container '" + global.container + "' not found.\nRun '" + profile.commandPrefix() + " start' to launch one.");
                return 1;
            }
        } catch (DockerException e) {
            printer.error("Failed to inspect container: " + e.getMessage());
            return 1;
        }

        if (service != null) {
            printer.warn("--service filtering is not yet supported. Showing all container logs.");
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
