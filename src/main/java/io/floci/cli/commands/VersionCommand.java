package io.floci.cli.commands;

import io.floci.cli.CliVersion;
import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
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
        description = "Show CLI version, connected Floci AWS server version, and image digest",
        mixinStandardHelpOptions = true
)
public class VersionCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public VersionCommand() {
        this(ProductProfile.AWS);
    }

    protected VersionCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();

        String serverVersion = null;
        String serverEdition = null;
        String imageDigest = null;

        var docker = new io.floci.cli.docker.DockerClient();
        String effectiveEndpoint = global.resolvedEndpoint(docker);

        FlociHttpClient client = new FlociHttpClient(effectiveEndpoint, profile.controlPrefix());
        try {
            var info = client.info();
            serverVersion = info.version();
            serverEdition = info.edition();
        } catch (Exception ignored) {}

        // Try to read image digest from Docker (best-effort)
        try {
            imageDigest = docker.imageDigest(profile.image()).orElse(null);
        } catch (Exception ignored) {}

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cli", CliVersion.CLI_VERSION);
        data.put("server", Optional.ofNullable(serverVersion).orElse("unavailable"));
        data.put("edition", Optional.ofNullable(serverEdition).orElse(""));
        if (imageDigest != null) data.put("digest", imageDigest);

        if (printer.format() != OutputFormat.text) {
            printer.structured(data);
            return 0;
        }

        printer.println(Ansi.bold("Floci CLI") + "  " + Ansi.gold(CliVersion.CLI_VERSION));
        if (serverVersion != null) {
            printer.println("Server:      " + serverVersion + (serverEdition != null ? " (" + serverEdition + ")" : ""));
        } else {
            // Effective endpoint (port auto-detected) — the gcp/az/oci trees always did
            // this; the old AWS copy printed the raw --endpoint value.
            printer.println("Server:      " + Ansi.gray("not reachable at " + effectiveEndpoint));
        }
        if (imageDigest != null) {
            printer.println("Image:       " + imageDigest);
        }

        return 0;
    }
}
