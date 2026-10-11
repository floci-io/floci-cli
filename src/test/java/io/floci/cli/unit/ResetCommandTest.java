package io.floci.cli.unit;

import com.sun.net.httpserver.HttpServer;
import io.floci.cli.FlociCli;
import io.floci.cli.ProductProfile;
import io.floci.cli.commands.ResetCommand;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** BL-064: floci reset against a stub emulator. */
class ResetCommandTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    private record Run(int exit, String out, String err) {}

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] body = "{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
            if (status.get() == 200) {
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } else {
                exchange.sendResponseHeaders(status.get(), -1);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Run run(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--endpoint", "http://127.0.0.1:" + server.getAddress().getPort(),
                "--container", "floci-reset-test-none"));
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            int exit = FlociCli.buildCommandLine(new ProfileStore(tempDir)).execute(all.toArray(String[]::new));
            return new Run(exit, outBuf.toString(), errBuf.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    @Test
    void yesPostsToTheProductsResetEndpoint() {
        Run r = run("gcp", "reset", "--yes");

        assertEquals(0, r.exit(), r.err());
        assertEquals(List.of("POST /_floci-gcp/state/reset"), requests);
        assertTrue(r.out().contains("state reset"), r.out());
    }

    @Test
    void structuredOutputReportsTheReset() {
        Run r = run("reset", "-y", "-o", "json");

        assertEquals(0, r.exit(), r.err());
        assertEquals(List.of("POST /_floci/state/reset"), requests);
        assertTrue(r.out().contains("\"reset\" : true") || r.out().contains("\"reset\":true"), r.out());
    }

    /** Nobody to ask: without --yes nothing is deleted and the exit code says it was a usage problem. */
    @Test
    void withoutATerminalItNeedsYes() {
        Run r = run("oci", "reset");

        assertEquals(2, r.exit());
        assertTrue(r.err().contains("Pass --yes"), r.err());
        assertEquals(List.of(), requests, "nothing was reset");
    }

    @Test
    void aServerWithoutTheEndpointSaysResetIsNotAvailable() {
        status.set(404);

        Run r = run("az", "reset", "--yes");

        assertEquals(1, r.exit());
        assertTrue(r.err().contains("not available"), r.err());
        assertTrue(r.err().contains("floci-az"), r.err());
    }

    // ---- which server a reset acts on -------------------------------------------------------

    private static DockerClient dockerWith(String name, String state, String ports) {
        return new DockerClient() {
            @Override
            public Optional<ContainerInfo> inspectContainer(String asked) {
                return asked.equals(name)
                        ? Optional.of(new ContainerInfo("abc", name, "floci/floci:latest", state, ports))
                        : Optional.empty();
            }
        };
    }

    private static final ResetCommand.Prompt NOBODY = new ResetCommand.Prompt() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String ask(String question) {
            throw new AssertionError("nobody to ask");
        }
    };

    private static ResetCommand.Target targetOf(DockerClient docker, String... args) {
        ResetCommand reset = new ResetCommand(ProductProfile.AWS, docker, NOBODY);
        new CommandLine(reset).parseArgs(args);
        return reset.target();
    }

    /** A typed --endpoint is the server to reset, even with a local container of the same name running. */
    @Test
    void aTypedEndpointIsNeverReplacedByALocalContainer() {
        DockerClient local = dockerWith("floci", "running", "14566->4566/tcp");

        ResetCommand.Target target = targetOf(local, "--endpoint", "http://remote-host:4566");

        assertEquals("http://remote-host:4566", target.endpoint());
        assertNull(target.error());
    }

    @Test
    void theDefaultContainerIsFoundThroughItsPortMapping() {
        ResetCommand.Target target = targetOf(dockerWith("floci", "running", "14566->4566/tcp"));

        assertEquals("http://localhost:14566", target.endpoint());
    }

    /** A container the user chose and that is not there must not fall back to the default instance. */
    @Test
    void aChosenContainerThatIsMissingResetsNothing() {
        ResetCommand.Target target = targetOf(dockerWith("floci", "running", "4566->4566/tcp"),
                "--container", "floci-team-b");

        assertNull(target.endpoint());
        assertTrue(target.error().contains("Container 'floci-team-b' not found, so nothing was reset."), target.error());
        assertTrue(target.error().contains("floci status --container floci-team-b"), target.error());
        assertTrue(target.error().contains("--endpoint <url>"), target.error());
    }

    /** A stopped container has no port mapping; the fallback would again be the default instance. */
    @Test
    void aChosenContainerThatIsStoppedResetsNothing() {
        ResetCommand.Target target = targetOf(dockerWith("floci-team-b", "exited", ""), "--container", "floci-team-b");

        assertNull(target.endpoint());
        assertTrue(target.error().contains("is not running (state: exited)"), target.error());
    }

    /**
     * Running, but with no published port for the emulator (or only an unrelated one): there is
     * nothing to find it by, and the configured endpoint may be the default instance.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "18080->8080/tcp"})
    void aChosenContainerWithoutAMatchingPortResetsNothing(String ports) {
        ResetCommand.Target target = targetOf(dockerWith("floci-team-b", "running", ports), "--container", "floci-team-b");

        assertNull(target.endpoint());
        assertTrue(target.error().contains("publishes no port for the emulator, so nothing was reset."), target.error());
    }

    @Test
    void aChosenContainerIsFoundThroughItsPortMapping() {
        ResetCommand.Target target = targetOf(dockerWith("floci-team-b", "running", "14566->4566/tcp"),
                "--container", "floci-team-b");

        assertEquals("http://localhost:14566", target.endpoint());
    }

    /**
     * An instance on its own host port: the profile's endpoint names that host port (14566), the
     * container still listens on the emulator's (4566). It is found by the latter.
     */
    @Test
    void anInstanceOnACustomHostPortIsFoundByTheEmulatorsPort() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("team-b.yaml"),
                "container: floci-team-b\nendpoint: http://localhost:14566\nport: 14566\n");
        ResetCommand reset = new ResetCommand(ProductProfile.AWS,
                dockerWith("floci-team-b", "running", "14566->4566/tcp"), NOBODY);
        new CommandLine(reset)
                .setDefaultValueProvider(new ProfileDefaultValueProvider(new ProfileStore(tempDir)))
                .parseArgs("--profile", "team-b");

        ResetCommand.Target target = reset.target();

        assertNull(target.error(), target.error());
        assertEquals("http://localhost:14566", target.endpoint());
    }

    @Test
    void theOtherProductsAreFoundByTheirOwnPort() {
        ResetCommand gcp = new ResetCommand(ProductProfile.GCP, dockerWith("floci-gcp-b", "running", "14588->4588/tcp"), NOBODY);
        new CommandLine(gcp).parseArgs("--container", "floci-gcp-b");

        assertEquals("http://localhost:14588", gcp.target().endpoint());
    }

    /** The default container without a published port (host networking) is still the default instance. */
    @Test
    void theDefaultContainerWithoutAPublishedPortUsesItsConfiguredEndpoint() {
        ResetCommand.Target target = targetOf(dockerWith("floci", "running", ""));

        assertEquals("http://localhost:4566", target.endpoint());
    }

    @Test
    void aChosenContainerDockerCannotLookUpResetsNothing() {
        DockerClient broken = new DockerClient() {
            @Override
            public Optional<ContainerInfo> inspectContainer(String name) throws DockerException {
                throw new DockerException("Cannot connect to the Docker daemon");
            }
        };

        ResetCommand.Target target = targetOf(broken, "--container", "floci-team-b");

        assertNull(target.endpoint());
        assertTrue(target.error().contains("Cannot connect to the Docker daemon"), target.error());
    }

    @Test
    void aChosenContainerWithATypedEndpointUsesTheEndpoint() {
        ResetCommand.Target target = targetOf(dockerWith("floci", "running", "4566->4566/tcp"),
                "--container", "floci-team-b", "--endpoint", "http://localhost:14577");

        assertEquals("http://localhost:14577", target.endpoint());
    }

    /** Without any selection the default instance is meant, also when it runs outside a 'floci' container. */
    @Test
    void theDefaultInstanceFallsBackToItsConfiguredEndpoint() {
        ResetCommand.Target target = targetOf(dockerWith("something-else", "running", ""));

        assertEquals("http://localhost:4566", target.endpoint());
    }

    @Test
    void aMissingChosenContainerSendsNoRequest() {
        ResetCommand reset = new ResetCommand(ProductProfile.AWS, dockerWith("floci", "running", "4566->4566/tcp"), NOBODY);

        Run r = execute(reset, "--yes", "--container", "floci-team-b");

        assertEquals(1, r.exit());
        assertTrue(r.err().contains("nothing was reset"), r.err());
        assertEquals(List.of(), requests);
    }

    // ---- the confirmation --------------------------------------------------------------------

    private static ResetCommand.Prompt answering(String answer, List<String> asked) {
        return new ResetCommand.Prompt() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String ask(String question) {
                asked.add(question);
                return answer;
            }
        };
    }

    private Run execute(ResetCommand reset, String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            int exit = new CommandLine(reset).execute(args);
            return new Run(exit, outBuf.toString(), errBuf.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private Run confirmWith(String answer, List<String> asked) {
        ResetCommand reset = new ResetCommand(ProductProfile.AWS, dockerWith("none", "running", ""), answering(answer, asked));
        return execute(reset, "--endpoint", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Anything but a yes deletes nothing: a no, a bare Enter, something else. */
    @ParameterizedTest
    @ValueSource(strings = {"n", "N", "no", "", "  ", "yep", "y es"})
    void decliningSendsNoRequest(String answer) {
        List<String> asked = new ArrayList<>();

        Run r = confirmWith(answer, asked);

        assertEquals(1, r.exit());
        assertEquals(List.of(), requests, "nothing was reset");
        assertTrue(r.out().contains("Reset cancelled."), r.out());
        assertEquals(1, asked.size());
    }

    /** End of input (Ctrl-D) is not a yes. */
    @Test
    void endOfInputSendsNoRequest() {
        Run r = confirmWith(null, new ArrayList<>());

        assertEquals(1, r.exit());
        assertEquals(List.of(), requests, "nothing was reset");
    }

    @ParameterizedTest
    @ValueSource(strings = {"y", "Y", "yes", "YES", " yes "})
    void confirmingSendsExactlyOneRequest(String answer) {
        List<String> asked = new ArrayList<>();

        Run r = confirmWith(answer, asked);

        assertEquals(0, r.exit(), r.err());
        assertEquals(List.of("POST /_floci/state/reset"), requests);
        assertTrue(asked.get(0).contains("http://127.0.0.1:" + server.getAddress().getPort()), asked.get(0));
    }

    /** --yes asks nobody. */
    @Test
    void yesDoesNotAsk() {
        List<String> asked = new ArrayList<>();
        ResetCommand reset = new ResetCommand(ProductProfile.AWS, dockerWith("none", "running", ""), answering("n", asked));

        Run r = execute(reset, "--yes", "--endpoint", "http://127.0.0.1:" + server.getAddress().getPort());

        assertEquals(0, r.exit(), r.err());
        assertEquals(List.of(), asked);
        assertEquals(List.of("POST /_floci/state/reset"), requests);
    }
}
