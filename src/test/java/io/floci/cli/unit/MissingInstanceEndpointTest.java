package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.commands.DoctorCommand;
import io.floci.cli.commands.StatusCommand;
import io.floci.cli.commands.VersionCommand;
import io.floci.cli.commands.WaitCommand;
import io.floci.cli.commands.config.ConfigProfileCommand;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.checks.EndpointReachableCheck;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MissingInstanceEndpointTest {
    static List<ProductProfile> products() {
        return List.of(ProductProfile.AWS, ProductProfile.GCP, ProductProfile.AZ, ProductProfile.OCI);
    }

    @TempDir Path temp;
    private final DockerClient missing = new DockerClient() {
        @Override public Optional<ContainerInfo> inspectContainer(String name) { return Optional.empty(); }
    };

    @ParameterizedTest
    @MethodSource("products")
    void legacyProfilePortWorksBeforeStartupInEveryTree(ProductProfile product) throws Exception {
        Files.writeString(temp.resolve("other.yaml"), "container: other\nport: 14500\nendpoint: "
                + product.defaultEndpoint() + "\n");
        List<String> args = new ArrayList<>(List.of(product.name().toLowerCase(), "env", "--profile", "other"));
        var parsed = FlociCli.buildCommandLine(new ProfileStore(temp)).parseArgs(args.toArray(String[]::new));
        while (parsed.hasSubcommand()) parsed = parsed.subcommand();
        GlobalOptions global = (GlobalOptions) parsed.commandSpec().mixins().get("global").userObject();
        List<String> warnings = new ArrayList<>();
        assertEquals("http://localhost:14500", global.resolvedEndpoint(missing, warnings::add));
        assertTrue(warnings.getFirst().contains("--profile other"));
        assertEquals("http://localhost:16000", global.endpointFromPorts("16000->" + product.defaultPort() + "/tcp", global.endpoint));
    }

    @ParameterizedTest
    @MethodSource("products")
    void aCreatedInstanceResolvesToItsPortWithoutStoringAnEndpointNobodyTyped(ProductProfile product) throws Exception {
        ProfileStore store = new ProfileStore(temp);
        assertEquals(0, new CommandLine(new ConfigProfileCommand(product, store))
                .execute("create", "other", "--container", "other", "--port", "14500"));
        assertEquals(product.defaultEndpoint(), store.get("other").orElseThrow().endpoint);
        var parsed = FlociCli.buildCommandLine(store).parseArgs(product.name().toLowerCase(), "env", "--profile", "other");
        while (parsed.hasSubcommand()) parsed = parsed.subcommand();
        GlobalOptions global = (GlobalOptions) parsed.commandSpec().mixins().get("global").userObject();
        assertEquals("http://localhost:14500", global.endpoint);
    }

    private final DockerClient running = new DockerClient() {
        @Override public Optional<ContainerInfo> inspectContainer(String name) {
            return Optional.of(new ContainerInfo("id", name, "image", "running", "16000->4566/tcp"));
        }
    };

    @Test
    void aTypedEndpointBeatsARunningContainersPublishedPort() throws Exception {
        var parsed = FlociCli.buildCommandLine(new ProfileStore(temp))
                .parseArgs("env", "--container", "other", "--endpoint", "http://localhost:9999").subcommand();
        GlobalOptions global = (GlobalOptions) parsed.commandSpec().mixins().get("global").userObject();
        assertEquals("http://localhost:9999", global.resolvedEndpoint(running));
    }

    @Test
    void anEnvironmentEndpointBeatsARunningContainersPublishedPort() {
        GlobalOptions global = new GlobalOptions(ProductProfile.AWS,
                key -> key.equals("FLOCI_ENDPOINT") ? "http://localhost:9999" : null);
        assertEquals("http://localhost:9999", global.resolvedEndpoint(running));
    }

    @ParameterizedTest
    @MethodSource("products")
    void anEnvironmentEndpointOnTheDefaultPortIsStillAKnownEndpoint(ProductProfile product) {
        GlobalOptions fromEnv = new GlobalOptions(product,
                key -> key.equals(product.envVar("ENDPOINT")) ? product.defaultEndpoint() : null);
        StatusCommand status = new StatusCommand(product, missing) {
            { global = fromEnv; }
        };
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out));
            new CommandLine(status).execute("--container", "other", "--json");
        } finally {
            System.setOut(original);
        }
        assertTrue(out.toString().contains("\"endpoint\" : \"" + product.defaultEndpoint() + "\""), out.toString());
    }

    private GlobalOptions parsedEnv(ProductProfile product, String yaml) throws Exception {
        Files.writeString(temp.resolve("other.yaml"), yaml);
        var parsed = FlociCli.buildCommandLine(new ProfileStore(temp)).parseArgs(product.name().toLowerCase(), "env", "--profile", "other");
        while (parsed.hasSubcommand()) parsed = parsed.subcommand();
        return (GlobalOptions) parsed.commandSpec().mixins().get("global").userObject();
    }

    @ParameterizedTest
    @MethodSource("products")
    void aProfilePortEqualToTheDefaultIsStillAChosenPort(ProductProfile product) throws Exception {
        GlobalOptions global = parsedEnv(product, "container: other\nport: " + product.defaultPort() + "\n");
        assertTrue(global.canUseConfiguredEndpoint());
        assertEquals("http://localhost:" + product.defaultPort(), global.resolvedEndpoint(missing));
    }

    @ParameterizedTest
    @MethodSource("products")
    void aProfileWithNeitherPortNorEndpointStaysUnknown(ProductProfile product) throws Exception {
        GlobalOptions global = parsedEnv(product, "container: other\n");
        assertFalse(global.canUseConfiguredEndpoint());
        assertThrows(IllegalStateException.class, () -> global.resolvedEndpoint(missing));
    }

    @Test
    void aRunningContainerStillCorrectsAProfilePortEqualToTheDefault() throws Exception {
        GlobalOptions global = parsedEnv(ProductProfile.AWS, "container: other\nport: 4566\n");
        assertEquals("http://localhost:16000", global.resolvedEndpoint(running));
    }

    @Test
    void aProfileEndpointOnAnotherHostBeatsALocalContainer() throws Exception {
        Files.writeString(temp.resolve("other.yaml"), "container: other\nendpoint: http://remote:9000\n");
        var parsed = FlociCli.buildCommandLine(new ProfileStore(temp)).parseArgs("env", "--profile", "other").subcommand();
        GlobalOptions global = (GlobalOptions) parsed.commandSpec().mixins().get("global").userObject();
        assertEquals("http://remote:9000", global.resolvedEndpoint(running));
    }

    @Test
    void aRunningContainerCorrectsALoopbackProfileEndpoint() throws Exception {
        Files.writeString(temp.resolve("other.yaml"), "container: other\nport: 14500\n");
        var parsed = FlociCli.buildCommandLine(new ProfileStore(temp)).parseArgs("env", "--profile", "other").subcommand();
        GlobalOptions global = (GlobalOptions) parsed.commandSpec().mixins().get("global").userObject();
        assertEquals("http://localhost:16000", global.resolvedEndpoint(running));
    }

    @Test
    void aTypedEndpointDoesNotWarnAboutALocalContainer() throws Exception {
        var parsed = FlociCli.buildCommandLine(new ProfileStore(temp))
                .parseArgs("env", "--container", "other", "--endpoint", "http://remote:4566").subcommand();
        GlobalOptions global = (GlobalOptions) parsed.commandSpec().mixins().get("global").userObject();
        List<String> warnings = new ArrayList<>();
        global.resolvedEndpoint(missing, warnings::add);
        assertEquals(List.of(), warnings);
    }

    @Test
    void anUnreachableDaemonSaysSoInsteadOfSuggestingStart() {
        DockerClient down = new DockerClient() {
            @Override public Optional<ContainerInfo> inspectContainer(String name) throws DockerException {
                throw new DockerException("daemon down");
            }
        };
        GlobalOptions global = new GlobalOptions(ProductProfile.AWS, key -> null);
        global.container = "missing-other";
        var error = assertThrows(IllegalStateException.class, () -> global.resolvedEndpoint(down));
        assertTrue(error.getMessage().contains("Check that Docker is running"), error.getMessage());
        assertFalse(error.getMessage().contains("floci start"), error.getMessage());
    }

    private String[] captured(Callable<Integer> command, int expectedExit, String... args) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream(), stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout));
            System.setErr(new PrintStream(stderr));
            assertEquals(expectedExit, new CommandLine(command).execute(args), stderr.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return new String[]{stdout.toString(), stderr.toString()};
    }

    @ParameterizedTest
    @MethodSource("products")
    void doctorStillRunsAndDoesNotVouchForTheDefaultInstance(ProductProfile product) {
        Check reachable = new EndpointReachableCheck(product);
        String[] out = captured(new DoctorCommand(product, missing, List.of(reachable)), 0,
                "--container", "missing-other", "--json");
        assertTrue(out[0].contains("\"status\" : \"warn\""), out[0]);
        assertTrue(out[0].contains("is unknown"), out[0]);
        assertTrue(out[0].contains(product.commandPrefix() + " start --container missing-other"), out[0]);
    }

    @Test
    void waitForAMissingContainerIgnoresAServerOnTheDefaultPort() throws Exception {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 4566), 0);
        } catch (BindException e) {
            assumeTrue(false, "default port already occupied");
            return;
        }
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            captured(new WaitCommand(ProductProfile.AWS, missing), 1, "--container", "missing-other", "--timeout", "300ms");
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void waitFindsAContainerThatAppearsWhileItWaits() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        byte[] body = "{\"version\":\"2.2.0\",\"status\":\"running\"}".getBytes();
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        AtomicInteger lookups = new AtomicInteger();
        DockerClient appears = new DockerClient() {
            @Override public Optional<ContainerInfo> inspectContainer(String name) {
                if (lookups.incrementAndGet() < 3) return Optional.empty();
                return Optional.of(new ContainerInfo("id", name, "image", "running", server.getAddress().getPort() + "->4566/tcp"));
            }
        };
        try {
            String[] out = captured(new WaitCommand(ProductProfile.AWS, appears), 0, "--container", "late", "--timeout", "5s");
            assertTrue(out[0].contains(":" + server.getAddress().getPort()), out[0]);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void versionStillPrintsTheCliVersionForAMissingContainer() {
        String[] out = captured(new VersionCommand(ProductProfile.AWS, missing), 0, "--container", "missing-other", "--json");
        assertTrue(out[0].contains("\"server\" : \"unavailable\""), out[0]);
    }

    @Test
    void explicitEndpointWinsOverAProfilePort() throws Exception {
        Files.writeString(temp.resolve("other.yaml"), "container: other\nport: 14500\n");
        var parsed = FlociCli.buildCommandLine(new ProfileStore(temp)).parseArgs("env", "--profile", "other",
                "--endpoint", "http://remote:4566").subcommand();
        GlobalOptions global = (GlobalOptions) parsed.commandSpec().mixins().get("global").userObject();
        assertEquals("http://remote:4566", global.resolvedEndpoint(missing));
    }

    @ParameterizedTest
    @MethodSource("products")
    void unknownCustomContainerCannotProduceDefaultExports(ProductProfile product) {
        GlobalOptions global = new GlobalOptions(product, key -> null);
        global.container = "missing-other";
        var error = assertThrows(IllegalStateException.class, () -> global.resolvedEndpoint(missing));
        assertTrue(error.getMessage().contains("--endpoint"));
        assertTrue(error.getMessage().contains("--container missing-other"));
    }

    @Test
    void missingNamedContainerDoesNotProbeTheDefaultServer() throws Exception {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 4566), 0);
        } catch (BindException e) {
            assumeTrue(false, "default port already occupied");
            return;
        }
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"version\":\"2.2.0\",\"status\":\"running\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        PrintStream original = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out));
            assertEquals(1, new CommandLine(new StatusCommand(ProductProfile.AWS, missing))
                    .execute("--container", "missing-other", "--json"));
            assertEquals(0, requests.get());
            assertTrue(out.toString().contains("\"endpoint\" : null"), out.toString());
            assertTrue(out.toString().contains("\"reachable\" : false"), out.toString());
            assertTrue(out.toString().contains("unavailable"), out.toString());
        } finally {
            System.setOut(original);
            server.stop(0);
        }
    }
}
