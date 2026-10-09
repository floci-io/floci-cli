package io.floci.cli.unit;

import com.sun.net.httpserver.HttpServer;
import io.floci.cli.FlociCli;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
}
