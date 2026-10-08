package io.floci.cli.unit;

import io.floci.cli.azcli.AzCli;
import io.floci.cli.azcli.AzCliNotFoundException;
import io.floci.cli.commands.az.AzSetupCommand;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerClient.ContainerInfo;
import io.floci.cli.http.FlociException;
import io.floci.cli.http.TlsUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AzSetupCommandTest {

    @TempDir
    Path tempDir;

    private String pem;
    private FakeAz az;

    /** Records every az call; a command line can be made to fail, and 'cloud show' output set. */
    static class FakeAz implements AzCli {
        final List<List<String>> calls = new ArrayList<>();
        final List<Map<String, String>> envs = new ArrayList<>();
        final Set<String> failing = new HashSet<>();
        final Set<String> clouds = new HashSet<>();
        String activeCloud = "AzureCloud";
        boolean installed = true;

        @Override
        public Result run(List<String> args, Map<String, String> env) throws AzCliNotFoundException {
            if (!installed) throw new AzCliNotFoundException("nope");
            calls.add(List.copyOf(args));
            envs.add(new HashMap<>(env));
            String line = String.join(" ", args);
            if (failing.stream().anyMatch(line::startsWith)) return new Result(1, "ERROR: " + line);
            if (line.startsWith("cloud show --query name")) return new Result(0, activeCloud + "\n");
            if (line.startsWith("cloud show -n ")) return new Result(clouds.contains(args.get(3)) ? 0 : 3, "");
            if (line.startsWith("cloud register -n ")) clouds.add(args.get(3));
            if (line.startsWith("cloud set -n ")) activeCloud = args.get(3);
            return new Result(0, "");
        }

        List<String> lines() {
            return calls.stream().map(c -> String.join(" ", c)).toList();
        }
    }

    /** Never touches Docker: no container found, so the configured endpoint is used. */
    static class NoDocker extends DockerClient {
        @Override
        public Optional<ContainerInfo> inspectContainer(String name) {
            return Optional.empty();
        }
    }

    /** Where an instance's state lands: {@code <root>/az/<container>/}. */
    private Path dir(String container) {
        return tempDir.resolve("az").resolve(container);
    }

    @BeforeEach
    void setUp() throws Exception {
        pem = AzTestCerts.somePem();
        az = new FakeAz();
    }

    private int run(AzSetupCommand.CertSource certs, String... args) {
        return new CommandLine(new AzSetupCommand(tempDir, az, certs, new NoDocker(), Duration.ZERO)).execute(args);
    }

    private int run(String... args) {
        return run(endpoint -> pem, args);
    }

    private String stdout(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString();
    }

    @Test
    void runsTheCompatSuiteRecipeAgainstTheIsolatedConfig() throws Exception {
        assertEquals(0, run("--endpoint", "http://localhost:4577"));

        assertEquals(List.of(
                "cloud show -n floci-az -o none",
                "cloud register -n floci-az"
                        + " --endpoint-resource-manager https://localhost:4577/"
                        + " --endpoint-active-directory https://localhost:4577"
                        + " --endpoint-active-directory-resource-id https://localhost:4577/"
                        + " --endpoint-active-directory-graph-resource-id https://localhost:4577/"
                        + " --suffix-storage-endpoint core.windows.net"
                        + " --suffix-keyvault-dns .vault.azure.net"
                        + " --suffix-acr-login-server-endpoint .azurecr.io",
                "cloud set -n floci-az",
                "config set core.instance_discovery=false",
                "login --service-principal -u 00000000-0000-0000-0000-000000000003 -p fake-secret"
                        + " --tenant 00000000-0000-0000-0000-000000000002 -o none",
                "account set --subscription 00000000-0000-0000-0000-000000000001"), az.lines());

        Path configDir = dir("floci-az").resolve("azure-config");
        Path bundle = dir("floci-az").resolve("ca-bundle.pem");
        assertTrue(Files.isDirectory(configDir));
        assertTrue(Files.readString(bundle).endsWith(pem.strip() + "\n"));
        for (Map<String, String> env : az.envs) {
            assertEquals(configDir.toAbsolutePath().toString(), env.get("AZURE_CONFIG_DIR"));
            assertEquals(bundle.toAbsolutePath().toString(), env.get("REQUESTS_CA_BUNDLE"));
        }
    }

    @Test
    void reRunningUpdatesTheCloudInsteadOfRegisteringItAgain() {
        assertEquals(0, run());
        az.calls.clear();

        assertEquals(0, run());

        assertTrue(az.lines().get(1).startsWith("cloud update -n floci-az "), az.lines().toString());
    }

    @Test
    void globalLeavesTheDefaultConfigDirAndUnsetsAnInheritedOne() {
        assertEquals(0, run("--global"));

        assertFalse(Files.exists(dir("floci-az").resolve("azure-config")));
        for (Map<String, String> env : az.envs) {
            // Present with a null value: the runner removes it, so an eval'd 'floci az env'
            // in the user's shell cannot send a --global setup into the isolated config.
            assertTrue(env.containsKey("AZURE_CONFIG_DIR"));
            assertNull(env.get("AZURE_CONFIG_DIR"));
            assertNotNull(env.get("REQUESTS_CA_BUNDLE"));
        }
    }

    @Test
    void switchingAnInstanceToGlobalRemovesItsEarlierIsolatedConfig() {
        assertEquals(0, run());
        assertTrue(Files.isDirectory(dir("floci-az").resolve("azure-config")));

        assertEquals(0, run("--global"));

        // Otherwise 'floci az env' would keep selecting the old isolated login.
        assertFalse(Files.exists(dir("floci-az").resolve("azure-config")));
    }

    @Test
    void loginRetriesWithoutRequiringASubscription() {
        // Fails the first login only: FakeAz.failing matches by prefix, which would also catch the retry.
        AzCli failFirstLogin = new AzCli() {
            boolean failed;

            @Override
            public Result run(List<String> args, Map<String, String> env) throws AzCliNotFoundException {
                if (args.get(0).equals("login") && !failed) {
                    failed = true;
                    az.calls.add(List.copyOf(args));
                    return new Result(1, "No subscriptions found");
                }
                return az.run(args, env);
            }
        };
        int exit = new CommandLine(new AzSetupCommand(tempDir, failFirstLogin, e -> pem, new NoDocker(), Duration.ZERO))
                .execute();

        assertEquals(0, exit);
        List<String> logins = az.lines().stream().filter(l -> l.startsWith("login")).toList();
        assertEquals(2, logins.size());
        assertTrue(logins.get(1).endsWith("--allow-no-subscriptions"));
    }

    @Test
    void aFailingStepStopsTheSetupWithExitOne() {
        az.failing.add("cloud set");

        assertEquals(1, run());

        assertTrue(az.lines().stream().noneMatch(l -> l.startsWith("login")), az.lines().toString());
    }

    @Test
    void aSubscriptionThatCannotBeSelectedIsOnlyAWarning() {
        az.failing.add("account set");

        assertEquals(0, run());
    }

    @Test
    void tlsOffFailsBeforeAnyAzCall() {
        assertEquals(1, run(e -> {
            throw new TlsUnavailableException("TLS is not enabled", false);
        }));
        assertTrue(az.calls.isEmpty());
    }

    @Test
    void anUnreachableServerFailsBeforeAnyAzCall() {
        assertEquals(1, run(e -> {
            throw new FlociException("Connection refused at http://localhost:4577.");
        }));
        assertTrue(az.calls.isEmpty());
    }

    @Test
    void waitsWhileTheCertificateIsStillBeingGenerated() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AzSetupCommand.CertSource slow = e -> {
            if (attempts.incrementAndGet() < 3) throw new TlsUnavailableException("certificate not available yet", true);
            return pem;
        };

        assertEquals(pem, AzSetupCommand.fetchWithRetry(slow, "http://x", 5, Duration.ZERO));
        assertEquals(3, attempts.get());
    }

    @Test
    void givesUpWaitingAfterTheLastAttempt() {
        AzSetupCommand.CertSource never = e -> {
            throw new TlsUnavailableException("certificate not available yet", true);
        };

        TlsUnavailableException e = assertThrows(TlsUnavailableException.class,
                () -> AzSetupCommand.fetchWithRetry(never, "http://x", 3, Duration.ZERO));
        assertTrue(e.notYet());
    }

    @Test
    void missingAzCliExitsOne() {
        az.installed = false;

        assertEquals(1, run());
    }

    @Test
    void jsonOutputReportsWhatWasConfigured() {
        String out = stdout(() -> assertEquals(0, run("-o", "json")));

        for (String key : new String[]{"container", "configDir", "global", "caBundle", "cloud", "cloudRegistered",
                "endpoint", "tenant", "subscription", "subscriptionSet"}) {
            assertTrue(out.contains("\"" + key + "\""), key + " missing in " + out);
        }
        assertTrue(out.contains("\"endpoint\" : \"https://localhost:4577\""), out);
    }

    @Test
    void resetRemovesTheIsolatedConfigWithoutCallingAz() {
        assertEquals(0, run());
        az.calls.clear();

        assertEquals(0, run("--reset"));

        assertFalse(Files.exists(dir("floci-az").resolve("azure-config")));
        assertFalse(Files.exists(dir("floci-az").resolve("ca-bundle.pem")));
        assertFalse(Files.exists(dir("floci-az")), "an emptied instance dir is removed too");
        assertTrue(az.calls.isEmpty());
    }

    @Test
    void globalResetSwitchesBackOnlyWhenFlociAzIsActive() {
        az.activeCloud = "floci-az";
        assertEquals(0, run("--reset", "--global"));
        assertEquals(List.of(
                "cloud show --query name -o tsv",
                "cloud set -n AzureCloud",
                "config set core.instance_discovery=true"), az.lines());

        az.calls.clear();
        assertEquals(0, run("--reset", "--global"));
        assertEquals(List.of("cloud show --query name -o tsv"), az.lines());
    }

    @Test
    void eachContainerGetsItsOwnConfigAndCloud() {
        assertEquals(0, run("--container", "floci-az-a", "--endpoint", "http://localhost:14577"));
        assertEquals(0, run("--container", "floci-az-b", "--endpoint", "http://localhost:14578"));

        assertTrue(Files.isDirectory(dir("floci-az-a").resolve("azure-config")));
        assertTrue(Files.isDirectory(dir("floci-az-b").resolve("azure-config")));
        assertTrue(az.lines().contains("cloud set -n floci-az-a"), az.lines().toString());
        assertTrue(az.lines().contains("cloud set -n floci-az-b"), az.lines().toString());
        assertTrue(az.lines().stream().anyMatch(l -> l.startsWith("cloud register -n floci-az-b")
                && l.contains("https://localhost:14578/")), az.lines().toString());

        assertEquals(0, run("--container", "floci-az-a", "--reset"));

        assertFalse(Files.exists(dir("floci-az-a").resolve("azure-config")));
        assertTrue(Files.isDirectory(dir("floci-az-b").resolve("azure-config")));
    }

    @Test
    void globalResetOnlyUndoesItsOwnCloud() {
        az.activeCloud = "floci-az-b";

        assertEquals(0, run("--container", "floci-az-a", "--reset", "--global"));

        assertEquals(List.of("cloud show --query name -o tsv"), az.lines());
    }

    @Test
    void aContainerNameThatWouldEscapeIsRefused() {
        assertEquals(1, run("--container", "../x"));
        assertTrue(az.calls.isEmpty());
    }

    @Test
    void httpsKeepsTheHostAndPort() {
        assertEquals("https://localhost:4577", AzSetupCommand.httpsOf("http://localhost:4577"));
        assertEquals("https://127.0.0.1:14577", AzSetupCommand.httpsOf("http://127.0.0.1:14577/"));
        assertEquals("https://localhost:4577", AzSetupCommand.httpsOf("http://localhost"));
    }
}
