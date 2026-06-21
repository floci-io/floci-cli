package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
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
        description = "Start the Floci container",
        mixinStandardHelpOptions = true
)
public class StartCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions global;

    @Option(names = {"--port"}, description = "Host port to bind (default: 4566)", defaultValue = "4566", paramLabel = "<port>")
    int port;

    @Option(names = {"--persist"}, description = "Host directory for persistent state", paramLabel = "<dir>")
    String persistDir;

    @Option(names = {"--services"}, description = "Comma-separated list of services to enable", paramLabel = "<csv>")
    String services;

    @Option(names = {"--detach"}, description = "Return immediately without waiting for readiness")
    boolean detach;

    @Option(names = {"--image"}, description = "Image reference to use (default: floci/floci:latest)", defaultValue = "floci/floci:latest", paramLabel = "<ref>")
    String image;

    @Option(names = {"--pull"}, description = "Image pull policy: always, missing, never", defaultValue = "missing", paramLabel = "always|missing|never")
    String pull;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        DockerClient docker = new DockerClient();

        // Verify docker is available
        if (!DockerClient.isInstalled()) {
            printer.error("docker binary not found in PATH.\nInstall Docker Desktop from https://docs.docker.com/get-docker/");
            return 1;
        }

        // Check if container already exists
        try {
            var existing = docker.inspectContainer(global.container);
            if (existing.isPresent()) {
                String state = existing.get().state();
                if ("running".equals(state)) {
                    printer.error("Container '" + global.container + "' is already running.\nRun 'floci stop' first or pass --container <name> to use a different name.");
                    return 1;
                }
                // Remove stopped container so we can start fresh
                printer.println(Ansi.gray("Removing stopped container '" + global.container + "'..."));
                docker.removeContainer(global.container);
            }
        } catch (DockerException e) {
            printer.error("Failed to inspect container: " + e.getMessage());
            return 1;
        }

        // Pull image if needed
        try {
            printer.println(Ansi.gray("Checking image " + image + " (policy: " + pull + ")..."));
            docker.pull(image, pull);
        } catch (DockerException e) {
            printer.error("Failed to pull image: " + e.getMessage() + "\nRun 'floci start --pull never' to skip pulling.");
            return 1;
        }

        // Build docker run arguments
        List<String> args = new ArrayList<>();
        args.addAll(List.of("-d", "--name", global.container));
        args.addAll(List.of("-p", port + ":4566"));
        args.addAll(DockerClient.dockerSocketRunArgs());
        if (persistDir != null) {
            args.addAll(List.of("-v", persistDir + ":/app/data"));
            // The server defaults to in-memory storage; enable persistent mode so
            // state is actually written to the mounted directory and survives restarts.
            args.addAll(List.of("-e", "FLOCI_STORAGE_MODE=persistent"));
        }
        if (services != null && !services.isBlank()) {
            args.addAll(List.of("-e", "FLOCI_SERVICES=" + services));
        }
        args.add(image);

        try {
            printer.println("Starting " + Ansi.gold("Floci") + " container...");
            String id = docker.startContainer(args);
            printer.println(Ansi.green("Container started") + " (" + id.substring(0, Math.min(12, id.length())) + ")");
        } catch (DockerException e) {
            printer.error("Failed to start container: " + e.getMessage());
            return 1;
        }

        if (detach) {
            printer.println(Ansi.gray("Detached. Run 'floci wait' to poll for readiness."));
            return 0;
        }

        // Update endpoint to match the bound host port before polling readiness
        global.endpoint = "http://localhost:" + port;

        // Wait for readiness
        printer.println(Ansi.gray("Waiting for Floci to be ready..."));
        WaitCommand wait = new WaitCommand();
        wait.global = global;
        wait.timeout = "30s";
        return wait.call();
    }
}
