package io.floci.cli.commands.gcp;

import io.floci.cli.GcpGlobalOptions;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "start",
        description = "Start the Floci GCP emulator container",
        mixinStandardHelpOptions = true
)
public class GcpStartCommand implements Callable<Integer> {

    @Mixin
    GcpGlobalOptions global;

    @Option(names = {"--port"}, description = "Host port to bind (default: 4588)", defaultValue = "4588", paramLabel = "<port>")
    int port;

    @Option(names = {"--persist"}, description = "Host directory for persistent state", paramLabel = "<dir>")
    String persistDir;

    @Option(names = {"--services"}, description = "Comma-separated list of services to enable", paramLabel = "<csv>")
    String services;

    @Option(names = {"--detach"}, description = "Return immediately without waiting for readiness")
    boolean detach;

    @Option(names = {"--image"}, description = "Image reference to use (default: floci/floci-gcp:latest)", defaultValue = "floci/floci-gcp:latest", paramLabel = "<ref>")
    String image;

    @Option(names = {"--pull"}, description = "Image pull policy: always, missing, never", defaultValue = "missing", paramLabel = "always|missing|never")
    String pull;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        DockerClient docker = new DockerClient();

        if (!DockerClient.isInstalled()) {
            printer.error("docker binary not found in PATH.\nInstall Docker Desktop from https://docs.docker.com/get-docker/");
            return 1;
        }

        try {
            var existing = docker.inspectContainer(global.container);
            if (existing.isPresent()) {
                String state = existing.get().state();
                if ("running".equals(state)) {
                    printer.error("Container '" + global.container + "' is already running.\nRun 'floci gcp stop' first or pass --container <name> to use a different name.");
                    return 1;
                }
                printer.println(Ansi.gray("Removing stopped container '" + global.container + "'..."));
                docker.removeContainer(global.container);
            }
        } catch (DockerException e) {
            printer.error("Failed to inspect container: " + e.getMessage());
            return 1;
        }

        try {
            printer.println(Ansi.gray("Checking image " + image + " (policy: " + pull + ")..."));
            docker.pull(image, pull);
        } catch (DockerException e) {
            printer.error("Failed to pull image: " + e.getMessage() + "\nRun 'floci gcp start --pull never' to skip pulling.");
            return 1;
        }

        List<String> args = new ArrayList<>();
        args.addAll(List.of("-d", "--name", global.container));
        args.addAll(List.of("-p", port + ":4588"));
        args.addAll(DockerClient.dockerSocketRunArgs());
        if (persistDir != null) {
            args.addAll(List.of("-v", persistDir + ":/app/data"));
            // The server defaults to in-memory storage; enable persistent mode so
            // state is actually written to the mounted directory and survives restarts.
            args.addAll(List.of("-e", "FLOCI_GCP_STORAGE_MODE=persistent"));
        }
        if (services != null && !services.isBlank()) {
            args.addAll(List.of("-e", "FLOCI_GCP_SERVICES=" + services));
        }
        args.add(image);

        try {
            printer.println("Starting " + Ansi.gold("Floci GCP") + " container...");
            String id = docker.startContainer(args);
            printer.println(Ansi.green("Container started") + " (" + id.substring(0, Math.min(12, id.length())) + ")");
        } catch (DockerException e) {
            printer.error("Failed to start container: " + e.getMessage());
            return 1;
        }

        if (detach) {
            printer.println(Ansi.gray("Detached. Run 'floci gcp wait' to poll for readiness."));
            return 0;
        }

        global.endpoint = "http://localhost:" + port;

        printer.println(Ansi.gray("Waiting for Floci GCP to be ready..."));
        GcpWaitCommand wait = new GcpWaitCommand();
        wait.global = global;
        wait.timeout = "30s";
        return wait.call();
    }
}