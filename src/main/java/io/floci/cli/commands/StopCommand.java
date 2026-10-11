package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

@Command(
        name = "stop",
        description = "Stop the Floci AWS container",
        mixinStandardHelpOptions = true
)
public class StopCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public StopCommand() {
        this(ProductProfile.AWS);
    }

    protected StopCommand(ProductProfile profile) {
        this(profile, new DockerClient());
    }

    /** Test seam: {@code docker} answers every docker call this stop makes. */
    public StopCommand(ProductProfile profile, DockerClient docker) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
        this.docker = docker;
    }

    private final DockerClient docker;

    /**
     * Set by {@code restart}, whose start reports the outcome: this stop then prints no structured
     * document of its own, so stdout carries exactly one.
     */
    boolean embedded;

    @Option(names = {"--remove", "-r"}, description = "Remove the container after stopping")
    boolean remove;

    @Option(names = {"--timeout"}, description = "Seconds to wait before forcefully killing; -1 waits indefinitely (default: 10)", defaultValue = "10", paramLabel = "<seconds>")
    int timeout;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        boolean structured = printer.format() != OutputFormat.text;
        Consumer<String> progress = structured ? line -> { } : printer::println;

        try {
            var info = docker.inspectContainer(global.container);
            if (info.isEmpty()) {
                printer.error("Container '" + global.container + "' not found.\nRun '" + profile.commandPrefix() + " start' to launch one.");
                return 1;
            }
            if (!"running".equals(info.get().state())) {
                progress.accept(Ansi.yellow("Container '" + global.container + "' is not running (state: " + info.get().state() + ")"));
                if (remove) {
                    docker.removeContainer(global.container);
                    progress.accept("Container removed.");
                }
                report(printer, structured, remove ? "removed" : info.get().state(), false, remove);
                return 0;
            }
        } catch (DockerException e) {
            printer.error("Failed to inspect container: " + e.withNextStep(DockerClient.CHECK_DOCKER));
            return 1;
        }

        try {
            progress.accept("Stopping " + Ansi.gold(profile.displayName()) + " container '" + global.container + "'...");
            docker.stopContainer(global.container, timeout);
            progress.accept(Ansi.green("Stopped."));
        } catch (DockerException e) {
            printer.error("Failed to stop container: " + e.withNextStep(DockerClient.CHECK_DOCKER));
            return 1;
        }

        if (remove) {
            try {
                docker.removeContainer(global.container);
                progress.accept("Container removed.");
            } catch (DockerException e) {
                printer.error("Failed to remove container: " + e.withNextStep(DockerClient.CHECK_DOCKER));
                return 1;
            }
        }

        report(printer, structured, remove ? "removed" : "exited", true, remove);
        return 0;
    }

    // What -o json/yaml reports for a stop: the state the container was left in, whether this
    // call is what stopped it, and whether it was removed.
    private void report(Printer printer, boolean structured, String state, boolean stopped, boolean removed) {
        if (!structured || embedded) return;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("container", global.container);
        result.put("state", state);
        result.put("stopped", stopped);
        result.put("removed", removed);
        printer.structured(result);
    }
}
