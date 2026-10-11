package io.floci.cli.commands.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Glyphs;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "validate",
        description = "Validate a docker-compose.yml file for Floci AWS compatibility",
        mixinStandardHelpOptions = true
)
public class ConfigValidateCommand implements Callable<Integer> {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

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
            printer.error("No docker-compose file found.\nPass --file <path>, or run in a directory with docker-compose.yml.");
            return 1;
        }

        String content;
        try {
            content = Files.readString(composeFile);
        } catch (IOException e) {
            printer.error("Could not read " + composeFile + ": " + e.getMessage(),
                    "Check the file's permissions, or pass another with --file.");
            return 1;
        }

        JsonNode compose = null;
        String unreadable = null;
        try {
            compose = YAML.readTree(content);
            if (compose == null || !compose.isObject()) {
                unreadable = "Could not read " + composeFile + " as a Compose file: it has no top-level mapping";
            }
        } catch (IOException e) {
            String reason = e.getMessage() == null ? "unreadable" : e.getMessage().lines().findFirst().orElse("unreadable");
            unreadable = "Could not read " + composeFile + " as YAML: " + reason;
        }
        if (unreadable != null) {
            // Structured output always carries the report, so a script reading it gets 'valid: false'
            // and the reason rather than nothing.
            if (printer.format() != OutputFormat.text) {
                printer.structured(document(composeFile, new ComposeCheck.Report(null, List.of(unreadable), List.of())));
            } else {
                printer.error(unreadable, "Fix the file's syntax, or pass another with --file.");
            }
            return 1;
        }

        Path baseDir = composeFile.toAbsolutePath().getParent();
        ComposeCheck.Report report = ComposeCheck.analyze(compose, profile, dockerSockRequired, dockerSockNeededFor, baseDir);

        if (printer.format() != OutputFormat.text) {
            printer.structured(document(composeFile, report));
            return report.valid() ? 0 : 1;
        }

        printer.println(Ansi.bold("Validating") + " " + composeFile
                + (report.service() == null ? "" : Ansi.gray("  (service: " + report.service() + ")")));
        printer.println("");

        for (String issue : report.errors()) {
            printer.println("  " + Ansi.red(Glyphs.fail()) + "  " + issue);
        }
        for (String warn : report.warnings()) {
            printer.println("  " + Ansi.yellow(Glyphs.warn()) + "  " + warn);
        }
        if (report.errors().isEmpty() && report.warnings().isEmpty()) {
            printer.println("  " + Ansi.green(Glyphs.ok()) + "  Compose file looks good");
        }

        printer.println("");
        if (!report.valid()) {
            printer.println(report.errors().size() + " error(s) found.");
            return 1;
        }
        return 0;
    }

    private static Map<String, Object> document(Path composeFile, ComposeCheck.Report report) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", composeFile.toString());
        out.put("service", report.service());
        out.put("valid", report.valid());
        out.put("errors", report.errors());
        out.put("warnings", report.warnings());
        return out;
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
