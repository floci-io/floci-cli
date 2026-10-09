package io.floci.cli.commands.az;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.InstanceState;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.doctor.checks.AzCliConnectionStringCheck;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import io.floci.cli.output.ShellExport;
import picocli.CommandLine.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
            description = "Shell format: bash, zsh, sh, fish, powershell (default: bash)",
            defaultValue = "bash",
            paramLabel = "<shell>")
    String shell;

    private final Path stateRoot;
    private final AzSetupCommand.CertSource certSource;

    public AzEnvCommand() {
        this(InstanceState.defaultRoot(),
                endpoint -> new FlociHttpClient(endpoint, ProductProfile.AZ.controlPrefix()).tlsCert());
    }

    /**
     * Test seam: {@code stateRoot} stands in for {@code ~/.floci}, where 'floci az setup' wrote
     * each instance's config dir and CA; {@code certSource} reads the running server's CA.
     */
    public AzEnvCommand(Path stateRoot, AzSetupCommand.CertSource certSource) {
        this.stateRoot = stateRoot;
        this.certSource = certSource;
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();
        if (!ShellExport.isSupported(shell)) {
            printer.error(ShellExport.unsupported(shell));
            return 2;
        }
        String effectiveEndpoint = global.resolvedEndpoint(new DockerClient());
        int port = extractPort(effectiveEndpoint);

        boolean sdkVarsMode = "sdk-vars".equals(format) || serviceFilter != null;
        List<String> requestedServices = resolveServices();
        Map<String, String> azCliVars = azCliVars(printer, effectiveEndpoint);

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
            out.putAll(azCliVars);
            printer.structured(out);
            return 0;
        }

        if (!sdkVarsMode) {
            String connStr = AzCliConnectionStringCheck.buildConnectionString(account, host, port);
            printer.println(ShellExport.formatExport(shell, "AZURE_STORAGE_CONNECTION_STRING", connStr));
            printAzCliVars(printer, azCliVars);
            printer.println("");
            printer.println(Ansi.gray("# Run: " + ShellExport.loadHint(shell, "floci az env")));
        } else {
            printer.println(ShellExport.formatExport(shell, "AZURE_STORAGE_ACCOUNT", account));
            printer.println(ShellExport.formatExport(shell, "AZURE_STORAGE_KEY", DEV_KEY));
            for (String svc : requestedServices) {
                printer.println(ShellExport.formatExport(shell, envVarName(svc), buildEndpointUrl(svc, host, port, account)));
            }
            printAzCliVars(printer, azCliVars);
            printer.println("");
            printer.println(Ansi.gray("# Run: " + ShellExport.loadHint(shell, "floci az env --format sdk-vars")));
        }

        return 0;
    }

    // Only after 'floci az setup' has run: point a plain 'az' in this shell at the isolated config
    // and make it trust floci-az's certificate. A --global setup leaves no config dir, so the
    // bundle is exported and AZURE_CONFIG_DIR is cleared (a null value).
    // The state is per instance (keyed by container), so --profile and --container pick the
    // setup that belongs to the emulator this env points at.
    private Map<String, String> azCliVars(Printer printer, String endpoint) {
        Map<String, String> vars = new LinkedHashMap<>();
        Path dir;
        try {
            dir = InstanceState.dir(stateRoot, ProductProfile.AZ, global.container);
        } catch (IllegalArgumentException e) {
            return vars; // not a usable container name; the storage variables still stand
        }
        Path configDir = dir.resolve(AzSetupCommand.CONFIG_DIR_NAME);
        Path bundle = dir.resolve(AzSetupCommand.BUNDLE_NAME);
        if (Files.isDirectory(configDir)) {
            vars.put("AZURE_CONFIG_DIR", configDir.toAbsolutePath().toString());
        } else if (Files.isRegularFile(bundle)) {
            // Set up with --global: clear an AZURE_CONFIG_DIR a previous 'eval $(floci az env)'
            // left in this shell, or plain 'az' keeps using that other instance's isolated login.
            vars.put("AZURE_CONFIG_DIR", null);
        }
        if (Files.isRegularFile(bundle)) vars.put("REQUESTS_CA_BUNDLE", bundle.toAbsolutePath().toString());
        warnIfCertificateChanged(printer, dir.resolve(AzSetupCommand.CA_NAME), endpoint);
        return vars;
    }

    // A container recreated without a persist dir generates a new CA, and the saved bundle then
    // fails every az call with a TLS error that says nothing about why. Warn on stderr, which
    // 'eval $(floci az env)' leaves visible. Any failure to fetch stays silent: env must keep
    // working with the emulator stopped.
    private void warnIfCertificateChanged(Printer printer, Path savedCa, String endpoint) {
        if (!Files.isRegularFile(savedCa)) return;
        try {
            String live = certSource.fetch(endpoint);
            if (!live.strip().equals(Files.readString(savedCa).strip())) {
                printer.warn("The Floci Azure certificate changed since 'floci az setup' ran.\n"
                        + "Run 'floci az setup" + global.instanceSelector() + "' to trust the new one.");
            }
        } catch (Exception ignored) {
        }
    }


    private void printAzCliVars(Printer printer, Map<String, String> vars) {
        vars.forEach((key, value) -> printer.println(value == null
                ? ShellExport.formatUnset(shell, key)
                : ShellExport.formatExport(shell, key, value)));
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

    private int extractPort(String endpoint) {
        try {
            int p = URI.create(endpoint).getPort();
            return p == -1 ? 4577 : p;
        } catch (Exception e) {
            return 4577;
        }
    }
}
