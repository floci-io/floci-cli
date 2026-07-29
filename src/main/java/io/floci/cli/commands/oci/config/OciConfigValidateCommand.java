package io.floci.cli.commands.oci.config;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

@Command(
        name = "validate",
        description = "Validate a docker-compose.yml file for Floci OCI compatibility",
        mixinStandardHelpOptions = true
)
public class OciConfigValidateCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Option(names = {"--file", "-f"}, description = "Path to docker-compose file", paramLabel = "<path>")
    String file;

    @Override
    public Integer call() {
        Printer printer = global.printer();

        Path composeFile = resolveComposeFile();
        if (composeFile == null) {
            printer.error("No docker-compose file found. Pass --file <path> or run in a directory with docker-compose.yml.");
            return 1;
        }

        String content;
        try {
            content = Files.readString(composeFile);
        } catch (IOException e) {
            printer.error("Could not read " + composeFile + ": " + e.getMessage());
            return 1;
        }

        printer.println(Ansi.bold("Validating") + " " + composeFile);
        printer.println("");

        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!content.contains("/var/run/docker.sock")) {
            warnings.add("Missing /var/run/docker.sock volume mount — required for Kafka/Redpanda support");
        }

        if (!Pattern.compile("4599\\s*:\\s*4599").matcher(content).find()) {
            issues.add("Port mapping 4599:4599 not found — Floci OCI listens on 4599 by default");
        }

        if (!content.contains("floci/floci-oci") && !content.contains("ghcr.io/floci-io/floci-oci")) {
            warnings.add("Floci OCI image reference not detected — ensure your service uses floci/floci-oci");
        }

        for (String issue : issues) {
            printer.println("  " + Ansi.red("✗") + "  " + issue);
        }
        for (String warn : warnings) {
            printer.println("  " + Ansi.yellow("⚠") + "  " + warn);
        }
        if (issues.isEmpty() && warnings.isEmpty()) {
            printer.println("  " + Ansi.green("✓") + "  Compose file looks good");
        }

        printer.println("");
        if (!issues.isEmpty()) {
            printer.println(issues.size() + " error(s) found.");
            return 1;
        }
        return 0;
    }

    private Path resolveComposeFile() {
        if (file != null) return Path.of(file);
        for (String name : List.of("docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml")) {
            Path p = Path.of(name);
            if (Files.exists(p)) return p;
        }
        return null;
    }
}
