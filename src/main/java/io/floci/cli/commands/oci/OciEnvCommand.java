package io.floci.cli.commands.oci;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "env",
        description = "Print OCI environment variables to connect to Floci OCI",
        mixinStandardHelpOptions = true
)
public class OciEnvCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Option(names = {"--shell"},
            description = "Shell format: bash, fish, powershell (default: bash)",
            defaultValue = "bash",
            paramLabel = "bash|fish|powershell")
    String shell;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        String endpoint = global.resolvedEndpoint(new DockerClient());

        Map<String, String> vars = new LinkedHashMap<>();
        // OCI SDKs and CLI take one endpoint for every service; ocilocal reads FLOCI_OCI_ENDPOINT.
        vars.put("OCI_CLI_ENDPOINT", endpoint);
        vars.put("FLOCI_OCI_ENDPOINT", endpoint);
        // The oracle/oci Terraform provider routes via per-client host overrides.
        vars.put("TF_VAR_CLIENT_HOST_OVERRIDES",
                "oci_identity.IdentityClient=" + endpoint
                        + ";oci_object_storage.ObjectStorageClient=" + endpoint);
        // Select the throwaway profile written by 'floci oci setup', when present.
        try {
            Path config = Path.of(System.getProperty("user.home"), ".oci", "config");
            if (OciSetupCommand.profileExists(config, "FLOCI")) {
                vars.put("OCI_CLI_PROFILE", "FLOCI");
            }
        } catch (Exception ignored) {
        }

        if (printer.format() != OutputFormat.text) {
            printer.structured(vars);
            return 0;
        }

        for (Map.Entry<String, String> entry : vars.entrySet()) {
            printer.println(formatExport(entry.getKey(), entry.getValue()));
        }
        printer.println("");
        printer.println(Ansi.gray("# Run: eval $(floci oci env)"));
        return 0;
    }

    private String formatExport(String key, String value) {
        // Values here can contain ';' (TF_VAR_CLIENT_HOST_OVERRIDES), so bash exports must be quoted.
        return switch (shell.toLowerCase()) {
            case "fish"               -> "set -x " + key + " \"" + value + "\"";
            case "powershell", "ps1"  -> "$env:" + key + " = \"" + value + "\"";
            default                   -> "export " + key + "=\"" + value + "\"";
        };
    }
}
