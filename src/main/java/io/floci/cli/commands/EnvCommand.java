package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import io.floci.cli.output.ShellExport;
import picocli.CommandLine.*;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "env",
        description = "Print AWS environment variables to connect to Floci AWS",
        mixinStandardHelpOptions = true
)
public class EnvCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions global;

    @Option(names = {"--host"},
            description = "Hostname for the AWS endpoint URL (default: localhost.floci.io)",
            defaultValue = "${FLOCI_HOST:-localhost.floci.io}",
            paramLabel = "<host>")
    String host;

    @Option(names = {"--region"},
            description = "AWS region (default: us-east-1)",
            defaultValue = "${AWS_DEFAULT_REGION:-us-east-1}",
            paramLabel = "<region>")
    String region;

    @Option(names = {"--shell"},
            description = "Shell format: bash, fish, powershell (default: bash)",
            defaultValue = "bash",
            paramLabel = "bash|fish|powershell")
    String shell;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        String effectiveEndpoint = global.resolvedEndpoint(new DockerClient());
        int port = extractPort(effectiveEndpoint);
        String endpointUrl = "http://" + host + ":" + port;

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("AWS_ENDPOINT_URL", endpointUrl);
        vars.put("AWS_ACCESS_KEY_ID", "test");
        vars.put("AWS_SECRET_ACCESS_KEY", "test");
        vars.put("AWS_DEFAULT_REGION", region);

        if (printer.format() != OutputFormat.text) {
            printer.structured(vars);
            return 0;
        }

        for (Map.Entry<String, String> entry : vars.entrySet()) {
            printer.println(ShellExport.formatExport(shell, entry.getKey(), entry.getValue()));
        }
        printer.println("");
        printer.println(Ansi.gray("# Run: eval $(floci env)"));
        return 0;
    }

    private int extractPort(String endpoint) {
        try {
            int port = URI.create(endpoint).getPort();
            return port == -1 ? 4566 : port;
        } catch (Exception e) {
            return 4566;
        }
    }
}
