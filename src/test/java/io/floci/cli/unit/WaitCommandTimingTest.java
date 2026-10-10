package io.floci.cli.unit;

import com.sun.net.httpserver.HttpServer;
import io.floci.cli.FlociCli;
import io.floci.cli.ProductProfile;
import io.floci.cli.commands.WaitCommand;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.docker.DockerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** BL-030: wait polls soon after a start and never runs past --timeout. */
class WaitCommandTimingTest {

    @TempDir
    Path tempDir;

    @Test
    void pollingBacksOffFromTwentyFiveMillisToHalfASecond() {
        assertEquals(50, WaitCommand.nextDelay(25));
        assertEquals(100, WaitCommand.nextDelay(50));
        assertEquals(200, WaitCommand.nextDelay(100));
        assertEquals(400, WaitCommand.nextDelay(200));
        assertEquals(500, WaitCommand.nextDelay(400));
        assertEquals(500, WaitCommand.nextDelay(500));
    }

    @Test
    void aRequestNeverOutlivesTheDeadline() {
        assertEquals(Duration.ofSeconds(10), WaitCommand.requestTimeout(Duration.ofMinutes(2)));
        assertEquals(Duration.ofMillis(800), WaitCommand.requestTimeout(Duration.ofMillis(800)));
        assertEquals(Duration.ofMillis(1), WaitCommand.requestTimeout(Duration.ZERO));
        assertEquals(Duration.ofMillis(1), WaitCommand.requestTimeout(Duration.ofMillis(-5)));
    }

    @Test
    void aServerThatNeverAnswersStillTimesOutOnTime() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(5_000); // longer than the timeout below
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        PrintStream err = System.err;
        PrintStream out = System.out;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            long start = System.nanoTime();
            int exit = FlociCli.buildCommandLine(new ProfileStore(tempDir)).execute(
                    "wait", "--timeout", "1s", "--endpoint", endpoint, "--container", "floci-wait-timing-none");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(1, exit);
            // The old loop could block for a full 10 s request; allow docker's inspect on top.
            assertTrue(elapsedMs < 2_500, "took " + elapsedMs + " ms");
        } finally {
            System.setErr(err);
            System.setOut(out);
            server.stop(0);
        }
    }

    /** A stalled Docker daemon must not hold wait past --timeout either. */
    @Test
    void aHungContainerLookupStillTimesOutOnTime() {
        DockerClient hung = new DockerClient() {
            @Override
            public Optional<ContainerInfo> inspectContainer(String name) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }
        };
        PrintStream err = System.err;
        PrintStream out = System.out;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            long start = System.nanoTime();
            // Port 9 (discard) refuses connections quickly, so only the lookup could stall.
            int exit = new CommandLine(new WaitCommand(ProductProfile.AWS, hung))
                    .execute("--timeout", "1s", "--endpoint", "http://127.0.0.1:9");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(1, exit);
            assertTrue(elapsedMs < 1_800, "took " + elapsedMs + " ms");
        } finally {
            System.setErr(err);
            System.setOut(out);
        }
    }

    /** Headers arrive at once, then the body stalls: HttpRequest.timeout alone would not cover it. */
    @Test
    void aServerThatStallsMidBodyStillTimesOutOnTime() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0); // chunked: the body never completes
            exchange.getResponseBody().write("{\"version\":".getBytes());
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        DockerClient noContainer = new DockerClient() {
            @Override
            public Optional<ContainerInfo> inspectContainer(String name) {
                return Optional.empty();
            }
        };
        PrintStream err = System.err;
        PrintStream out = System.out;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            long start = System.nanoTime();
            int exit = new CommandLine(new WaitCommand(ProductProfile.AWS, noContainer)).execute(
                    "--timeout", "1s", "--endpoint", "http://127.0.0.1:" + server.getAddress().getPort());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(1, exit);
            assertTrue(elapsedMs < 1_800, "took " + elapsedMs + " ms");
        } finally {
            System.setErr(err);
            System.setOut(out);
            server.stop(0);
        }
    }

    private record Out(int exit, String out, String err) {}

    private static Out waitWith(String... args) {
        return waitWith(false, args);
    }

    private static Out waitWith(boolean terminal, String... args) {
        DockerClient noContainer = new DockerClient() {
            @Override
            public Optional<ContainerInfo> inspectContainer(String name) {
                return Optional.empty();
            }
        };
        PrintStream err = System.err;
        PrintStream out = System.out;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            int exit = new CommandLine(new WaitCommand(ProductProfile.AWS, noContainer, () -> terminal)).execute(args);
            return new Out(exit, outBuf.toString(), errBuf.toString());
        } finally {
            System.setErr(err);
            System.setOut(out);
        }
    }

    /** BL-039: on a terminal, -o json and -o yaml still carry no spinner frames while polling. */
    @ParameterizedTest
    @ValueSource(strings = {"json", "yaml"})
    void structuredOutputHasNoSpinnerEvenOnATerminal(String format) throws Exception {
        Out r = waitWith(true, "--timeout", "300ms", "--endpoint", closedEndpoint(), "-o", format);

        assertEquals(1, r.exit());
        assertFalse(r.out().contains("Waiting"), r.out());
        assertFalse(r.out().contains("\r"), r.out());
    }

    /** The other half: text output on a terminal does draw it, so the test above can fail. */
    @Test
    void textOutputOnATerminalShowsTheSpinner() throws Exception {
        Out r = waitWith(true, "--timeout", "300ms", "--endpoint", closedEndpoint());

        assertEquals(1, r.exit());
        assertTrue(r.out().contains("Waiting"), r.out());
    }

    @Test
    void textOutputInAPipeHasNoSpinner() throws Exception {
        Out r = waitWith(false, "--timeout", "300ms", "--endpoint", closedEndpoint());

        assertEquals(1, r.exit());
        assertFalse(r.out().contains("Waiting"), r.out());
    }

    // A port that was just bound and released: nothing is listening, so polls are refused at once.
    private static String closedEndpoint() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    /** BL-040: a bad --timeout is a usage error with a hint, not a stack trace. */
    @Test
    void anUnreadableTimeoutExitsTwo() {
        Out r = waitWith("--timeout", "abc", "--endpoint", "http://127.0.0.1:9");

        assertEquals(2, r.exit());
        assertTrue(r.err().contains("Invalid duration 'abc'"), r.err());
    }
}
