package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Callable;

@Command(
        name = "version",
        description = "Show CLI version, connected server version, and image digest",
        mixinStandardHelpOptions = true
)
public class VersionCommand implements Callable<Integer> {

    public static final String CLI_VERSION = loadVersion();

    private static String loadVersion() {
        try (InputStream is = VersionCommand.class.getResourceAsStream("/io/floci/cli/version.properties")) {
            if (is == null) return "unknown";
            Properties props = new Properties();
            props.load(is);
            return props.getProperty("version", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Mixin
    GlobalOptions global;

    @Override
    public Integer call() {
        Printer printer = global.printer();

        String serverVersion = null;
        String serverEdition = null;
        String imageDigest = null;

        var docker = new io.floci.cli.docker.DockerClient();
        String effectiveEndpoint = global.resolvedEndpoint(docker);

        FlociHttpClient client = new FlociHttpClient(effectiveEndpoint);
        try {
            var info = client.info();
            serverVersion = info.version();
            serverEdition = info.edition();
        } catch (Exception ignored) {}

        // Try to read image digest from Docker (best-effort)
        try {
            imageDigest = docker.imageDigest("floci/floci").orElse(null);
        } catch (Exception ignored) {}

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cli", CLI_VERSION);
        data.put("server", Optional.ofNullable(serverVersion).orElse("unavailable"));
        data.put("edition", Optional.ofNullable(serverEdition).orElse(""));
        if (imageDigest != null) data.put("digest", imageDigest);

        if (printer.format() != OutputFormat.text) {
            printer.structured(data);
            return 0;
        }

        printer.println(Ansi.bold("Floci CLI") + "  " + Ansi.gold(CLI_VERSION));
        if (serverVersion != null) {
            printer.println("Server:      " + serverVersion + (serverEdition != null ? " (" + serverEdition + ")" : ""));
        } else {
            printer.println("Server:      " + Ansi.gray("not reachable at " + global.endpoint));
        }
        if (imageDigest != null) {
            printer.println("Image:       " + imageDigest);
        }

        return 0;
    }
}
