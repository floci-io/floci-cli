package io.floci.cli.commands.oci;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import io.floci.cli.output.ShellExport;
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
    GlobalOptions global = new GlobalOptions(ProductProfile.OCI);

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
        // DEFAULT needs no selection — the OCI CLI uses it implicitly.
        try {
            Path config = Path.of(System.getProperty("user.home"), ".oci", "config");
            String setupProfile = OciSetupCommand.detectSetupProfile(config);
            if (setupProfile != null && !"DEFAULT".equals(setupProfile)) {
                vars.put("OCI_CLI_PROFILE", setupProfile);
            }
        } catch (Exception ignored) {
        }

        if (printer.format() != OutputFormat.text) {
            printer.structured(vars);
            return 0;
        }

        for (Map.Entry<String, String> entry : vars.entrySet()) {
            printer.println(ShellExport.formatExport(shell, entry.getKey(), entry.getValue()));
        }
        printer.println("");
        printer.println(Ansi.gray("# Run: eval $(floci oci env)"));
        return 0;
    }
}
