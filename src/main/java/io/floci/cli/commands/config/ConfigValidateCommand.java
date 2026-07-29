package io.floci.cli.commands.config;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
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
        description = "Validate a docker-compose.yml file for Floci AWS compatibility",
        mixinStandardHelpOptions = true
)
public class ConfigValidateCommand implements Callable<Integer> {

    protected final ProductProfile profile;
    private final boolean dockerSockRequired;
    private final String dockerSockNeededFor;

    @Mixin
    protected GlobalOptions global;

    public ConfigValidateCommand() {
        this(ProductProfile.AWS, true, "Lambda, EC2, EKS, MSK, ECR, CodeBuild");
    }

    protected ConfigValidateCommand(ProductProfile profile, boolean dockerSockRequired, String dockerSockNeededFor) {
        this.profile = profile;
        this.dockerSockRequired = dockerSockRequired;
        this.dockerSockNeededFor = dockerSockNeededFor;
        this.global = new GlobalOptions(profile);
    }

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

        // Check for docker.sock mount (an error where the product needs it for core services)
        if (!content.contains("/var/run/docker.sock")) {
            (dockerSockRequired ? issues : warnings)
                    .add("Missing /var/run/docker.sock volume mount — required for " + dockerSockNeededFor);
        }

        // Check port mapping
        int port = profile.defaultPort();
        if (!Pattern.compile(port + "\\s*:\\s*" + port).matcher(content).find()) {
            issues.add("Port mapping " + port + ":" + port + " not found — " + profile.displayName()
                    + " listens on " + port + " by default");
        }

        // Check for the product image
        String ghcrImage = "ghcr.io/floci-io/" + profile.image().substring(profile.image().indexOf('/') + 1);
        if (!content.contains(profile.image()) && !content.contains(ghcrImage)) {
            warnings.add(profile.displayName() + " image reference not detected — ensure your service uses "
                    + profile.image() + " or " + ghcrImage);
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
