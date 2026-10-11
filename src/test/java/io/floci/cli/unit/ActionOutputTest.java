package io.floci.cli.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.floci.cli.ProductProfile;
import io.floci.cli.commands.RestartCommand;
import io.floci.cli.commands.StartCommand;
import io.floci.cli.commands.StatusCommand;
import io.floci.cli.commands.StopCommand;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerClient.DockerHost;
import io.floci.cli.docker.DockerClient.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BL-056 and BL-057: start, stop and restart print one structured document with -o json/yaml and
 * nothing else on stdout, and status says through its exit code whether the instance is up.
 */
class ActionOutputTest {

    // A remote daemon, so start does not probe the local port its own stub server listens on.
    private static final DockerHost REMOTE = new DockerHost(Kind.TCP, null, "tcp://build-host:2375");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private HttpServer emulator;
    private int port;

    @BeforeEach
    void startEmulatorStub() throws Exception {
        emulator = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        emulator.createContext("/", exchange -> {
            byte[] body = "{\"version\":\"1.6.0\",\"edition\":\"community\",\"services\":[\"s3\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        emulator.start();
        port = emulator.getAddress().getPort();
    }

    @AfterEach
    void stopEmulatorStub() {
        emulator.stop(0);
    }

    /** A docker that holds one container (or none) and records what was asked of it. */
    private static class FakeDocker extends DockerClient {
        final List<String> calls = new ArrayList<>();
        String state; // null: no such container
        String ports = "";

        FakeDocker(String state) {
            this.state = state;
        }

        @Override
        public DockerHost daemonHost() {
            return REMOTE;
        }

        @Override
        public Optional<ContainerInfo> inspectContainer(String name) {
            return state == null ? Optional.empty()
                    : Optional.of(new ContainerInfo("abc123", name, "floci/floci:latest", state, ports));
        }

        @Override
        public Optional<RunSettings> inspectRunSettings(String name, int containerPort) {
            return Optional.empty();
        }

        @Override
        public void pull(String image, String policy) {
            calls.add("pull:stdout");
            System.out.println("latest: Pulling from floci/floci");
        }

        @Override
        public void pull(String image, String policy, PrintStream progress) {
            calls.add("pull:" + (progress == System.err ? "stderr" : "other"));
            progress.println("latest: Pulling from floci/floci");
        }

        @Override
        public String startContainer(List<String> args) {
            calls.add("run");
            state = "running";
            return "0123456789abcdef0123";
        }

        @Override
        public void stopContainer(String name, int timeout) {
            calls.add("stop");
            state = "exited";
        }

        @Override
        public void removeContainer(String name) {
            calls.add("rm");
            state = null;
        }
    }

    private record Run(int exit, String out, String err) {}

    private Run execute(Callable<Integer> command, String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            int exit = new CommandLine(command)
                    .setDefaultValueProvider(new ProfileDefaultValueProvider(new ProfileStore(tempDir)))
                    .execute(args);
            return new Run(exit, outBuf.toString(), errBuf.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private String[] startArgs(String... more) {
        List<String> args = new ArrayList<>(List.of("--port", String.valueOf(port), "--container", "floci-actions"));
        args.addAll(List.of(more));
        return args.toArray(String[]::new);
    }

    // ---- start -----------------------------------------------------------------------------------

    @Test
    void startPrintsOneJsonDocumentAndKeepsProgressOffStdout() throws Exception {
        FakeDocker docker = new FakeDocker(null);

        Run r = execute(new StartCommand(ProductProfile.AWS, docker), startArgs("-o", "json", "--pull", "always"));

        assertEquals(0, r.exit(), r.err());
        JsonNode json = JSON.readTree(r.out()); // fails if anything but the document is on stdout
        assertEquals("floci-actions", json.path("container").asText());
        assertEquals("0123456789abcdef0123", json.path("id").asText());
        assertEquals("floci/floci:latest", json.path("image").asText());
        assertEquals("http://localhost:" + port, json.path("endpoint").asText());
        assertEquals("running", json.path("state").asText());
        assertTrue(json.path("ready").asBoolean());
        assertEquals(List.of("pull:stderr", "run"), docker.calls);
        assertTrue(r.err().contains("Pulling from"), "docker's pull progress goes to stderr: " + r.err());
        assertFalse(r.out().contains("Starting"), r.out());
    }

    @Test
    void aDetachedStartReportsNoReadiness() throws Exception {
        Run r = execute(new StartCommand(ProductProfile.AWS, new FakeDocker(null)), startArgs("-o", "json", "--detach"));

        assertEquals(0, r.exit(), r.err());
        JsonNode json = JSON.readTree(r.out());
        assertEquals("running", json.path("state").asText());
        assertFalse(json.has("ready"), "readiness was not waited for: " + r.out());
    }

    @Test
    void startInYamlIsOneDocumentToo() {
        Run r = execute(new StartCommand(ProductProfile.AWS, new FakeDocker(null)), startArgs("-o", "yaml", "--detach"));

        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().startsWith("container: \"floci-actions\""), r.out());
        assertFalse(r.out().contains("Detached."), r.out());
    }

    /** Text output is what it was: the progress lines, and docker's pull output on stdout. */
    @Test
    void startInTextStillNarrates() {
        FakeDocker docker = new FakeDocker(null);

        Run r = execute(new StartCommand(ProductProfile.AWS, docker), startArgs("--detach", "--pull", "always"));

        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("Container started"), r.out());
        assertTrue(r.out().contains("Detached."), r.out());
        assertEquals(List.of("pull:stdout", "run"), docker.calls);
        assertFalse(r.out().contains("\"container\""), r.out());
    }

    // ---- stop ------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"false, exited, stop", "true, removed, stop;rm"})
    void stopReportsWhatItDid(boolean remove, String state, String calls) throws Exception {
        FakeDocker docker = new FakeDocker("running");
        List<String> args = new ArrayList<>(List.of("-o", "json", "--container", "floci-actions"));
        if (remove) args.add("--remove");

        Run r = execute(new StopCommand(ProductProfile.AWS, docker), args.toArray(String[]::new));

        assertEquals(0, r.exit(), r.err());
        JsonNode json = JSON.readTree(r.out());
        assertEquals("floci-actions", json.path("container").asText());
        assertEquals(state, json.path("state").asText());
        assertTrue(json.path("stopped").asBoolean());
        assertEquals(remove, json.path("removed").asBoolean());
        assertEquals(List.of(calls.split(";")), docker.calls);
    }

    @Test
    void stoppingAContainerThatIsNotRunningSaysSo() throws Exception {
        Run r = execute(new StopCommand(ProductProfile.AWS, new FakeDocker("exited")), "-o", "json");

        assertEquals(0, r.exit(), r.err());
        JsonNode json = JSON.readTree(r.out());
        assertEquals("exited", json.path("state").asText());
        assertFalse(json.path("stopped").asBoolean());
        assertFalse(json.path("removed").asBoolean());
    }

    @Test
    void stopInTextStillNarrates() {
        Run r = execute(new StopCommand(ProductProfile.AWS, new FakeDocker("running")), "--remove");

        assertTrue(r.out().contains("Stopped."), r.out());
        assertTrue(r.out().contains("Container removed."), r.out());
    }

    // ---- restart ---------------------------------------------------------------------------------

    /** A restart is a stop and a start, but its structured output is the one document of the start. */
    @Test
    void restartPrintsOneDocument() throws Exception {
        FakeDocker docker = new FakeDocker("running");
        docker.ports = port + "->4566/tcp";
        RestartCommand restart = new RestartCommand(ProductProfile.AWS, docker) {
            @Override
            public StartCommand buildStartCommand() {
                StartCommand start = new StartCommand(ProductProfile.AWS, docker);
                new CommandLine(start).parseArgs(startArgs("-o", "json"));
                return start;
            }
        };

        Run r = execute(restart, "-o", "json", "--container", "floci-actions");

        assertEquals(0, r.exit(), r.err());
        JsonNode json = JSON.readTree(r.out());
        assertEquals("running", json.path("state").asText());
        assertTrue(json.path("ready").asBoolean());
        assertEquals(List.of("stop", "rm", "pull:stderr", "run"), docker.calls);
    }

    // ---- status ----------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "running,   true,  0",
            "running,   false, 1",
            "exited,    false, 1",
            "exited,    true,  1",   // something else answers there; this container is down
            "not found, true,  0",   // a remote endpoint, or a Compose service under another name
            "not found, false, 1",
            "'error: Cannot connect to the Docker daemon', true,  0",
            "'error: Cannot connect to the Docker daemon', false, 1"})
    void statusExitCodeSaysWhetherTheInstanceIsUp(String state, boolean reachable, int exit) {
        assertEquals(exit, StatusCommand.exitCode(state, reachable));
    }

    @Test
    void statusOfARunningInstanceExitsZero() throws Exception {
        FakeDocker docker = new FakeDocker("running");
        docker.ports = port + "->4566/tcp";

        Run json = execute(new StatusCommand(ProductProfile.AWS, docker), "-o", "json");
        Run text = execute(new StatusCommand(ProductProfile.AWS, docker));

        assertEquals(0, json.exit(), json.err());
        assertTrue(JSON.readTree(json.out()).path("reachable").asBoolean());
        assertEquals(0, text.exit());
        assertTrue(text.out().contains("Reachable:  yes"), text.out());
    }

    @Test
    void statusOfAnInstanceThatIsDownExitsOneInEveryFormat() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        FakeDocker docker = new FakeDocker(null);
        String endpoint = "http://127.0.0.1:" + closed;

        Run json = execute(new StatusCommand(ProductProfile.AWS, docker), "-o", "json", "--endpoint", endpoint);
        Run text = execute(new StatusCommand(ProductProfile.AWS, docker), "--endpoint", endpoint);

        assertEquals(1, json.exit());
        assertFalse(JSON.readTree(json.out()).path("reachable").asBoolean());
        assertEquals("not found", JSON.readTree(json.out()).path("state").asText());
        assertEquals(1, text.exit());
        assertTrue(text.out().contains("floci start"), text.out());
    }
}
