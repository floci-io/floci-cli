package io.floci.cli.commands.oci;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.commands.VersionCommand;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "version",
        description = "Show CLI version, connected Floci OCI server version, and image digest",
        mixinStandardHelpOptions = true
)
public class OciVersionCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Override
    public Integer call() {
        Printer printer = global.printer();

        String serverVersion = null;
        String serverEdition = null;
        String imageDigest = null;

        DockerClient docker = new DockerClient();
        String effectiveEndpoint = global.resolvedEndpoint(docker);

        FlociHttpClient client = new FlociHttpClient(effectiveEndpoint, OciGlobalOptions.CONTROL_PREFIX);
        try {
            var info = client.info();
            serverVersion = info.version();
            serverEdition = info.edition();
        } catch (Exception ignored) {}

        try {
            imageDigest = docker.imageDigest("floci/floci-oci").orElse(null);
        } catch (Exception ignored) {}

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cli", VersionCommand.CLI_VERSION);
        data.put("server", Optional.ofNullable(serverVersion).orElse("unavailable"));
        data.put("edition", Optional.ofNullable(serverEdition).orElse(""));
        if (imageDigest != null) data.put("digest", imageDigest);

        if (printer.format() != OutputFormat.text) {
            printer.structured(data);
            return 0;
        }

        printer.println(Ansi.bold("Floci CLI") + "  " + Ansi.gold(VersionCommand.CLI_VERSION));
        if (serverVersion != null) {
            printer.println("Server:      " + serverVersion + (serverEdition != null ? " (" + serverEdition + ")" : ""));
        } else {
            printer.println("Server:      " + Ansi.gray("not reachable at " + effectiveEndpoint));
        }
        if (imageDigest != null) {
            printer.println("Image:       " + imageDigest);
        }

        return 0;
    }
}
