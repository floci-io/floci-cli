package io.floci.cli.commands.gcp;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "env",
        description = "Print GCP environment variables to connect to Floci GCP",
        mixinStandardHelpOptions = true
)
public class GcpEnvCommand implements Callable<Integer> {

    private static final Set<String> ALL_SERVICES =
            Set.of("gcs", "pubsub", "firestore", "datastore", "secretmanager", "iam");

    @Mixin
    GlobalOptions global = new GlobalOptions(ProductProfile.GCP);

    @Option(names = {"--service"},
            description = "Comma-separated services: gcs,pubsub,firestore,datastore,secretmanager,iam",
            paramLabel = "<csv>")
    String serviceFilter;

    @Option(names = {"--shell"},
            description = "Shell format: bash, fish, powershell (default: bash)",
            defaultValue = "bash",
            paramLabel = "bash|fish|powershell")
    String shell;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        String effectiveEndpoint = global.resolvedEndpoint(new DockerClient());
        String host = extractHost(effectiveEndpoint);
        int port = extractPort(effectiveEndpoint);
        String hostPort = host + ":" + port;

        List<String> requestedServices = resolveServices();

        Map<String, String> vars = new LinkedHashMap<>();
        for (String svc : requestedServices) {
            String name = envVarName(svc);
            String value = buildValue(svc, host, port, hostPort);
            vars.put(name, value);
        }

        if (printer.format() != OutputFormat.text) {
            printer.structured(new LinkedHashMap<>(vars));
            return 0;
        }

        for (Map.Entry<String, String> entry : vars.entrySet()) {
            printer.println(formatExport(entry.getKey(), entry.getValue()));
        }
        printer.println("");
        printer.println(Ansi.gray("# Run: eval $(floci gcp env)"));

        return 0;
    }

    private List<String> resolveServices() {
        if (serviceFilter != null && !serviceFilter.isBlank()) {
            List<String> result = new ArrayList<>();
            for (String s : serviceFilter.split(",")) {
                String svc = s.trim().toLowerCase();
                if (ALL_SERVICES.contains(svc)) result.add(svc);
            }
            return result;
        }
        return List.of("gcs", "pubsub", "firestore", "datastore", "secretmanager");
    }

    private String envVarName(String service) {
        return switch (service) {
            case "gcs"           -> "STORAGE_EMULATOR_HOST";
            case "pubsub"        -> "PUBSUB_EMULATOR_HOST";
            case "firestore"     -> "FIRESTORE_EMULATOR_HOST";
            case "datastore"     -> "DATASTORE_EMULATOR_HOST";
            case "secretmanager" -> "SECRET_MANAGER_EMULATOR_HOST";
            case "iam"           -> "IAM_EMULATOR_HOST";
            default              -> service.toUpperCase().replace('-', '_') + "_EMULATOR_HOST";
        };
    }

    private String buildValue(String service, String host, int port, String hostPort) {
        // GCS SDK expects a full URL; the rest expect host:port
        if ("gcs".equals(service)) {
            return "http://" + hostPort;
        }
        return hostPort;
    }

    private String formatExport(String key, String value) {
        return switch (shell.toLowerCase()) {
            case "fish"               -> "set -x " + key + " \"" + value + "\"";
            case "powershell", "ps1"  -> "$env:" + key + " = \"" + value + "\"";
            default                   -> "export " + key + "=" + value;
        };
    }

    private String extractHost(String endpoint) {
        try {
            String h = URI.create(endpoint).getHost();
            return h != null ? h : "localhost";
        } catch (Exception e) {
            return "localhost";
        }
    }

    private int extractPort(String endpoint) {
        try {
            int p = URI.create(endpoint).getPort();
            return p == -1 ? 4588 : p;
        } catch (Exception e) {
            return 4588;
        }
    }
}
