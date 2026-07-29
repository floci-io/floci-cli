package io.floci.cli.commands.az;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.doctor.checks.AzCliConnectionStringCheck;
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
        description = "Print Azure environment variables to connect to Floci Azure",
        mixinStandardHelpOptions = true
)
public class AzEnvCommand implements Callable<Integer> {

    private static final String DEV_ACCOUNT = "devstoreaccount1";
    private static final String DEV_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==";

    private static final Set<String> ALL_SERVICES =
            Set.of("blob", "queue", "table", "functions", "app-config", "key-vault");

    @Mixin
    GlobalOptions global = new GlobalOptions(ProductProfile.AZ);

    @Option(names = {"--format"},
            description = "Output mode: connection-string, sdk-vars (default: connection-string)",
            defaultValue = "connection-string",
            paramLabel = "connection-string|sdk-vars")
    String format;

    @Option(names = {"--service"},
            description = "Comma-separated services for sdk-vars mode: blob,queue,table,functions,app-config,key-vault",
            paramLabel = "<csv>")
    String serviceFilter;

    @Option(names = {"--account"},
            description = "Storage account name (default: devstoreaccount1)",
            defaultValue = "${FLOCI_AZ_ACCOUNT:-devstoreaccount1}",
            paramLabel = "<name>")
    String account;

    @Option(names = {"--host"},
            description = "Hostname for endpoint URLs (default: localhost.floci.io)",
            defaultValue = "${FLOCI_AZ_HOST:-localhost.floci.io}",
            paramLabel = "<host>")
    String host;

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

        boolean sdkVarsMode = "sdk-vars".equals(format) || serviceFilter != null;
        List<String> requestedServices = resolveServices();

        if (printer.format() != OutputFormat.text) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (!sdkVarsMode) {
                out.put("AZURE_STORAGE_CONNECTION_STRING",
                        AzCliConnectionStringCheck.buildConnectionString(account, host, port));
            } else {
                out.put("AZURE_STORAGE_ACCOUNT", account);
                out.put("AZURE_STORAGE_KEY", DEV_KEY);
                for (String svc : requestedServices) {
                    out.put(envVarName(svc), buildEndpointUrl(svc, host, port, account));
                }
            }
            printer.structured(out);
            return 0;
        }

        if (!sdkVarsMode) {
            String connStr = AzCliConnectionStringCheck.buildConnectionString(account, host, port);
            printer.println(formatExport("AZURE_STORAGE_CONNECTION_STRING", connStr));
            printer.println("");
            printer.println(Ansi.gray("# Run: eval $(floci az env)"));
        } else {
            printer.println(formatExport("AZURE_STORAGE_ACCOUNT", account));
            printer.println(formatExport("AZURE_STORAGE_KEY", DEV_KEY));
            for (String svc : requestedServices) {
                printer.println(formatExport(envVarName(svc), buildEndpointUrl(svc, host, port, account)));
            }
            printer.println("");
            printer.println(Ansi.gray("# Run: eval $(floci az env --format sdk-vars)"));
        }

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
        return List.of("blob", "queue", "table", "functions", "app-config", "key-vault");
    }

    private String envVarName(String service) {
        return switch (service) {
            case "blob"       -> "AZURE_STORAGE_BLOB_ENDPOINT";
            case "queue"      -> "AZURE_STORAGE_QUEUE_ENDPOINT";
            case "table"      -> "AZURE_STORAGE_TABLE_ENDPOINT";
            case "functions"  -> "AZURE_FUNCTIONS_ENDPOINT";
            case "app-config" -> "AZURE_APP_CONFIGURATION_ENDPOINT";
            case "key-vault"  -> "AZURE_KEY_VAULT_ENDPOINT";
            default           -> "AZURE_" + service.toUpperCase().replace('-', '_') + "_ENDPOINT";
        };
    }

    private String buildEndpointUrl(String service, String host, int port, String accountName) {
        String base = "http://" + host + ":" + port + "/" + accountName;
        return switch (service) {
            case "blob"       -> base;
            case "queue"      -> base + "-queue";
            case "table"      -> base + "-table";
            case "functions"  -> base + "-functions";
            case "app-config" -> base + "-appconfig";
            case "key-vault"  -> base + "-keyvault";
            default           -> base + "-" + service;
        };
    }

    private String formatExport(String key, String value) {
        return switch (shell.toLowerCase()) {
            case "fish"               -> "set -x " + key + " \"" + value + "\"";
            case "powershell", "ps1"  -> "$env:" + key + " = \"" + value + "\"";
            default                   -> "export " + key + "=" + value;
        };
    }

    private int extractPort(String endpoint) {
        try {
            int p = URI.create(endpoint).getPort();
            return p == -1 ? 4577 : p;
        } catch (Exception e) {
            return 4577;
        }
    }
}
