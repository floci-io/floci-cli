package io.floci.cli.commands.oci;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

@Command(
        name = "stop",
        description = "Stop the Floci OCI container",
        mixinStandardHelpOptions = true
)
public class OciStopCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Option(names = {"--remove", "-r"}, description = "Remove the container after stopping")
    boolean remove;

    @Option(names = {"--timeout"}, description = "Seconds to wait before forcefully killing (default: 10)", defaultValue = "10", paramLabel = "<seconds>")
    int timeout;

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
            if (!"running".equals(info.get().state())) {
                printer.println(Ansi.yellow("Container '" + global.container + "' is not running (state: " + info.get().state() + ")"));
                if (remove) {
                    docker.removeContainer(global.container);
                    printer.println("Container removed.");
                }
                return 0;
            }
        } catch (DockerException e) {
            printer.error("Failed to inspect container: " + e.getMessage());
            return 1;
        }

        try {
            printer.println("Stopping " + Ansi.gold("Floci OCI") + " container '" + global.container + "'...");
            docker.stopContainer(global.container, timeout);
            printer.println(Ansi.green("Stopped."));
        } catch (DockerException e) {
            printer.error("Failed to stop container: " + e.getMessage());
            return 1;
        }

        if (remove) {
            try {
                docker.removeContainer(global.container);
                printer.println("Container removed.");
            } catch (DockerException e) {
                printer.error("Failed to remove container: " + e.getMessage());
                return 1;
            }
        }

        return 0;
    }
}