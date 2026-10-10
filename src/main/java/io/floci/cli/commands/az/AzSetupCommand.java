package io.floci.cli.commands.az;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.azcli.AzCli;
import io.floci.cli.azcli.AzCliNotFoundException;
import io.floci.cli.azcli.CaBundle;
import io.floci.cli.azcli.ProcessAzCli;
import io.floci.cli.config.InstanceState;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.http.FlociException;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.http.TlsUnavailableException;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(
        name = "setup",
        description = "Point the az CLI at Floci Azure: trust its certificate, register the cloud and log in",
        mixinStandardHelpOptions = true
)
public class AzSetupCommand implements Callable<Integer> {

    // Dev identity: floci-az's default tenant and subscription. The client id is any value (the
    // server echoes it back as appid) and the secret is never checked; these match floci-az's
    // compat-azcli suite.
    static final String TENANT = "00000000-0000-0000-0000-000000000002";
    static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    static final String CLIENT_ID = "00000000-0000-0000-0000-000000000003";
    static final String CLIENT_SECRET = "fake-secret";

    /** Under the instance dir: the isolated az config, read by {@code floci az env}. */
    public static final String CONFIG_DIR_NAME = "azure-config";
    /** Under the instance dir: the CA bundle, read by {@code floci az env}. */
    public static final String BUNDLE_NAME = "ca-bundle.pem";
    /** Under the instance dir: the floci-az CA alone, compared by {@code floci az env}. */
    public static final String CA_NAME = "floci-az-ca.pem";

    private static final int CERT_ATTEMPTS = 15;

    /** Fetches the floci-az CA PEM from an endpoint. A test seam. */
    @FunctionalInterface
    public interface CertSource {
        String fetch(String endpoint) throws FlociException;
    }

    @Mixin
    GlobalOptions global = new GlobalOptions(ProductProfile.AZ);

    @Option(names = {"--global"},
            description = "Configure your default az config (~/.azure) instead of an isolated one under ~/.floci/az")
    boolean globalConfig;

    @Option(names = {"--reset"},
            description = "Undo a previous setup: delete the isolated config, or with --global switch az back to AzureCloud")
    boolean reset;

    @Option(names = {"--tenant"}, description = "Tenant id to log in to (default: ${DEFAULT-VALUE})",
            defaultValue = TENANT, paramLabel = "<id>")
    String tenant;

    @Option(names = {"--subscription"}, description = "Subscription to select (default: ${DEFAULT-VALUE})",
            defaultValue = SUBSCRIPTION, paramLabel = "<id>")
    String subscription;

    @Option(names = {"--client-id"}, description = "Service principal client id (default: ${DEFAULT-VALUE})",
            defaultValue = CLIENT_ID, paramLabel = "<id>")
    String clientId;

    @Option(names = {"--client-secret"}, description = "Service principal secret; floci-az does not check it (default: ${DEFAULT-VALUE})",
            defaultValue = CLIENT_SECRET, paramLabel = "<secret>")
    String clientSecret;

    private final Path stateRoot;
    private final AzCli az;
    private final CertSource certSource;
    private final DockerClient docker;
    private final Duration certRetryDelay;

    public AzSetupCommand() {
        this(InstanceState.defaultRoot(), new ProcessAzCli(),
                endpoint -> new FlociHttpClient(endpoint, ProductProfile.AZ).tlsCert(),
                new DockerClient(), Duration.ofSeconds(2));
    }

    /** Test seam: {@code stateRoot} stands in for {@code ~/.floci}. */
    public AzSetupCommand(Path stateRoot, AzCli az, CertSource certSource, DockerClient docker,
                          Duration certRetryDelay) {
        this.stateRoot = stateRoot;
        this.az = az;
        this.certSource = certSource;
        this.docker = docker;
        this.certRetryDelay = certRetryDelay;
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();
        // One setup per instance: the container name keys both the state dir and the az cloud,
        // so '--profile team-b' never touches team-a's config, certificate or login.
        String cloud = global.container;
        Path flociAzDir;
        try {
            flociAzDir = InstanceState.dir(stateRoot, ProductProfile.AZ, cloud);
        } catch (IllegalArgumentException e) {
            printer.error(e.getMessage());
            return 1;
        }
        try {
            return reset ? reset(printer, flociAzDir, cloud) : setup(printer, flociAzDir, cloud);
        } catch (AzCliNotFoundException e) {
            printer.error("az CLI not found in PATH.\n"
                    + "Install it from https://learn.microsoft.com/cli/azure/install-azure-cli, then re-run 'floci az setup'.");
            return 1;
        } catch (StepFailedException e) {
            printer.error(e.getMessage()
                    + "\nFix the error above and re-run 'floci az setup', or run 'floci az setup --reset' to start over.");
            return 1;
        }
    }

    private int setup(Printer printer, Path flociAzDir, String cloud) throws AzCliNotFoundException, StepFailedException {
        String httpEndpoint = global.resolvedEndpoint(docker);
        String httpsEndpoint = httpsOf(httpEndpoint);
        Path configDir = flociAzDir.resolve(CONFIG_DIR_NAME);
        Path caFile = flociAzDir.resolve(CA_NAME);
        Path bundleFile = flociAzDir.resolve(BUNDLE_NAME);

        String caPem;
        try {
            caPem = fetchWithRetry(certSource, httpEndpoint, CERT_ATTEMPTS, certRetryDelay);
        } catch (TlsUnavailableException e) {
            printer.error(e.notYet()
                    ? "Floci Azure is still generating its TLS certificate.\nWait a few seconds and re-run 'floci az setup'."
                    : "Floci Azure is running without TLS, which the az CLI needs to log in.\n"
                            + "Run 'floci az restart' to enable it, then re-run 'floci az setup'.");
            return 1;
        } catch (FlociException e) {
            printer.error(e.getMessage() + "\nRun 'floci az start', then re-run 'floci az setup'.");
            return 1;
        }

        try {
            Files.createDirectories(flociAzDir);
            Files.writeString(caFile, caPem.strip() + "\n");
            Files.writeString(bundleFile, CaBundle.build(caPem));
            if (!globalConfig) {
                Files.createDirectories(configDir);
                // The az config dir holds the login's token cache.
                restrictPermissions(configDir);
            }
        } catch (Exception e) {
            printer.error("Could not write the az configuration: " + e.getMessage()
                    + "\nCheck that " + flociAzDir + " is writable and re-run 'floci az setup'.");
            return 1;
        }

        Map<String, String> env = azEnv(configDir, bundleFile);

        boolean cloudExisted = az.run(List.of("cloud", "show", "-n", cloud, "-o", "none"), env).ok();
        List<String> cloudArgs = new ArrayList<>(List.of("cloud", cloudExisted ? "update" : "register", "-n", cloud));
        cloudArgs.addAll(cloudEndpointArgs(httpsEndpoint));
        require(az.run(cloudArgs, env), "az cloud " + (cloudExisted ? "update" : "register"));
        require(az.run(List.of("cloud", "set", "-n", cloud), env), "az cloud set");
        // Without this MSAL validates the authority against login.microsoftonline.com and fails
        // with invalid_instance.
        require(az.run(List.of("config", "set", "core.instance_discovery=false"), env), "az config set");

        List<String> login = List.of("login", "--service-principal",
                "-u", clientId, "-p", clientSecret, "--tenant", tenant, "-o", "none");
        AzCli.Result loggedIn = az.run(login, env);
        if (!loggedIn.ok()) {
            List<String> retry = new ArrayList<>(login);
            retry.add("--allow-no-subscriptions");
            require(az.run(retry, env), "az login");
        }
        boolean subscriptionSet = az.run(List.of("account", "set", "--subscription", subscription), env).ok();

        // This instance now uses the default az config. An isolated config left by an earlier
        // setup would otherwise keep 'floci az env' selecting its old login.
        boolean isolatedRemoved = false;
        if (globalConfig && Files.isDirectory(configDir)) {
            try {
                deleteRecursively(configDir);
                isolatedRemoved = true;
            } catch (IOException e) {
                printer.warn("Could not remove the old isolated az config " + configDir.toAbsolutePath() + ": "
                        + e.getMessage() + "\nDelete it by hand, or 'floci az env' keeps selecting it.");
            }
        }

        if (printer.format() != OutputFormat.text) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("container", global.container);
            out.put("configDir", globalConfig ? null : configDir.toAbsolutePath().toString());
            out.put("global", globalConfig);
            out.put("caBundle", bundleFile.toAbsolutePath().toString());
            out.put("cloud", cloud);
            out.put("cloudRegistered", !cloudExisted);
            out.put("endpoint", httpsEndpoint);
            out.put("tenant", tenant);
            out.put("subscription", subscription);
            out.put("subscriptionSet", subscriptionSet);
            if (globalConfig) out.put("isolatedConfigRemoved", isolatedRemoved);
            printer.structured(out);
            return 0;
        }

        printer.println(Ansi.green("Trusted ") + "the Floci Azure certificate " + Ansi.gray("(" + caFile.toAbsolutePath() + ")"));
        printer.println((cloudExisted ? Ansi.gray("Updated ") : Ansi.green("Registered ")) + "cloud "
                + Ansi.bold(cloud) + " at " + httpsEndpoint);
        printer.println(Ansi.green("Logged in ") + "as " + clientId + " in tenant " + tenant
                + Ansi.gray(globalConfig ? " (default az config)" : " (" + configDir.toAbsolutePath() + ")"));
        if (isolatedRemoved) {
            printer.println(Ansi.gray("Removed the earlier isolated az config " + configDir.toAbsolutePath()));
        }
        if (!subscriptionSet) {
            printer.warn("Could not select subscription " + subscription
                    + ". Run 'az account list' to see the available ones.");
        }
        printer.println("");
        printer.println("Connect with:");
        // The same line for both modes: after --global, env exports the CA bundle and unsets an
        // AZURE_CONFIG_DIR an earlier isolated setup left in the shell (now deleted above).
        // A profile may set output: json, which env would inherit and print instead of exports.
        String textOnly = global.profile != null ? " -o text" : "";
        printer.println("  " + Ansi.bold("eval $(floci az env" + global.instanceSelector() + textOnly + ")"));
        printer.println("  " + Ansi.bold("az group list"));
        return 0;
    }

    private int reset(Printer printer, Path flociAzDir, String cloud) throws AzCliNotFoundException, StepFailedException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("container", global.container);
        out.put("global", globalConfig);

        if (globalConfig) {
            Map<String, String> env = azEnv(null, null);
            AzCli.Result active = az.run(List.of("cloud", "show", "--query", "name", "-o", "tsv"), env);
            boolean wasActive = active.ok() && cloud.equals(active.output().strip());
            if (wasActive) {
                require(az.run(List.of("cloud", "set", "-n", "AzureCloud"), env), "az cloud set");
                require(az.run(List.of("config", "set", "core.instance_discovery=true"), env), "az config set");
            }
            out.put("reset", wasActive);
            if (printer.format() != OutputFormat.text) {
                printer.structured(out);
            } else if (wasActive) {
                printer.println(Ansi.green("Switched ") + "the default az config back to " + Ansi.bold("AzureCloud"));
            } else {
                printer.println(Ansi.gray("Nothing to reset: ") + cloud + " is not the active az cloud.");
            }
            return 0;
        }

        boolean existed = Files.exists(flociAzDir.resolve(CONFIG_DIR_NAME));
        try {
            deleteRecursively(flociAzDir.resolve(CONFIG_DIR_NAME));
            Files.deleteIfExists(flociAzDir.resolve(BUNDLE_NAME));
            Files.deleteIfExists(flociAzDir.resolve(CA_NAME));
            deleteIfEmpty(flociAzDir);
        } catch (IOException e) {
            printer.error("Could not remove " + flociAzDir + ": " + e.getMessage()
                    + "\nDelete it by hand, then re-run 'floci az setup' if you need it again.");
            return 1;
        }
        out.put("reset", existed);
        if (printer.format() != OutputFormat.text) {
            printer.structured(out);
        } else if (existed) {
            printer.println(Ansi.green("Removed ") + "the isolated az config in " + flociAzDir.toAbsolutePath());
        } else {
            printer.println(Ansi.gray("Nothing to reset: ") + "no isolated az config in " + flociAzDir.toAbsolutePath());
        }
        return 0;
    }


    /**
     * The extra environment for every az call. A null value removes the variable, so a shell that
     * already ran {@code eval $(floci az env)} cannot redirect a {@code --global} setup into the
     * isolated config.
     */
    private Map<String, String> azEnv(Path configDir, Path bundleFile) {
        Map<String, String> env = new HashMap<>();
        env.put("AZURE_CONFIG_DIR", globalConfig || configDir == null ? null : configDir.toAbsolutePath().toString());
        if (bundleFile != null) {
            env.put("REQUESTS_CA_BUNDLE", bundleFile.toAbsolutePath().toString());
        }
        env.put("AZURE_CORE_ONLY_SHOW_ERRORS", "true");
        return env;
    }

    /** The {@code az cloud register|update} endpoint arguments, as floci-az's compat suite uses them. */
    static List<String> cloudEndpointArgs(String httpsEndpoint) {
        return List.of(
                "--endpoint-resource-manager", httpsEndpoint + "/",
                "--endpoint-active-directory", httpsEndpoint,
                "--endpoint-active-directory-resource-id", httpsEndpoint + "/",
                "--endpoint-active-directory-graph-resource-id", httpsEndpoint + "/",
                "--suffix-storage-endpoint", "core.windows.net",
                "--suffix-keyvault-dns", ".vault.azure.net",
                "--suffix-acr-login-server-endpoint", ".azurecr.io");
    }

    /** {@code http://host:port} to {@code https://host:port}: floci-az serves both on one port. */
    public static String httpsOf(String endpoint) {
        URI uri = URI.create(endpoint);
        int port = uri.getPort() == -1 ? ProductProfile.AZ.defaultPort() : uri.getPort();
        String host = uri.getHost() == null ? "localhost" : uri.getHost();
        return "https://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    /** Fetches the certificate, waiting while the server reports it is still being generated. */
    public static String fetchWithRetry(CertSource source, String endpoint, int attempts, Duration delay)
            throws FlociException {
        for (int i = 1; ; i++) {
            try {
                return source.fetch(endpoint);
            } catch (TlsUnavailableException e) {
                if (!e.notYet() || i >= attempts) throw e;
            }
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FlociException("Interrupted while waiting for the TLS certificate.");
            }
        }
    }

    private static void require(AzCli.Result result, String step) throws StepFailedException {
        if (result.ok()) return;
        List<String> lines = result.output().strip().lines().toList();
        String tail = String.join("\n", lines.subList(Math.max(0, lines.size() - 10), lines.size()));
        throw new StepFailedException(step + " failed (exit " + result.exit() + ")"
                + (tail.isEmpty() ? "." : ":\n" + tail));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void deleteIfEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> entries = Files.list(dir)) {
            if (entries.findAny().isEmpty()) Files.delete(dir);
        }
    }

    private static void restrictPermissions(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: no POSIX permissions; the directory inherits the user's ACLs.
        }
    }

    private static class StepFailedException extends Exception {
        StepFailedException(String message) {
            super(message);
        }
    }
}
