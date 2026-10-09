package io.floci.cli.unit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.floci.cli.CliVersion;
import io.floci.cli.commands.UpdateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BL-074: floci update against a stub release server. The installed binary is a file in a temp
 * directory, so a test can see whether it was replaced. Windows is out: update refuses to run there.
 */
@DisabledOnOs(OS.WINDOWS)
class UpdateCommandTest {

    private static final String NEWER = "99.0.0";
    private static final byte[] FRESH = "fresh binary".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OLD = "old binary".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicReference<String> latest = new AtomicReference<>("{\"tag_name\":\"" + NEWER + "\"}");
    private final AtomicReference<String> checksum = new AtomicReference<>(sha256(FRESH));
    private final List<String> downloads = new CopyOnWriteArrayList<>();

    private record Run(int exit, String out, String err) {}

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/latest", exchange -> respond(exchange, latest.get().getBytes(StandardCharsets.UTF_8)));
        server.createContext("/download/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            downloads.add(path);
            if (path.endsWith("/sha256sums.txt")) {
                StringBuilder sums = new StringBuilder();
                for (String platform : List.of("darwin-arm64", "darwin-amd64", "linux-arm64", "linux-amd64")) {
                    sums.append(checksum.get()).append("  ./floci-").append(platform).append("/floci-").append(platform).append('\n');
                }
                respond(exchange, sums.toString().getBytes(StandardCharsets.UTF_8));
            } else {
                respond(exchange, FRESH);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        exchange.close();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Path installed(Path dir) throws Exception {
        Files.createDirectories(dir);
        Path binary = dir.resolve("floci");
        Files.write(binary, OLD);
        return binary;
    }

    private Run update(Path binary, String... args) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        UpdateCommand command = new UpdateCommand(binary, base + "/api/latest", base + "/download");
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            int exit = new CommandLine(command).execute(args);
            return new Run(exit, outBuf.toString(), errBuf.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    @Test
    void replacesTheBinaryWithTheVerifiedDownload() throws Exception {
        Path binary = installed(tempDir.resolve("bin"));

        Run r = update(binary);

        assertEquals(0, r.exit(), r.err());
        assertArrayEquals(FRESH, Files.readAllBytes(binary));
        assertTrue(r.out().contains("checksum verified"), r.out());
        try (var left = Files.list(binary.getParent())) {
            assertEquals(List.of("floci"), left.map(p -> p.getFileName().toString()).toList(), "no staging file left behind");
        }
    }

    @Test
    void aChecksumMismatchLeavesTheBinaryAlone() throws Exception {
        Path binary = installed(tempDir.resolve("bin"));
        checksum.set(sha256("something else".getBytes(StandardCharsets.UTF_8)));

        Run r = update(binary);

        assertEquals(1, r.exit());
        assertTrue(r.err().contains("checksum mismatch"), r.err());
        assertArrayEquals(OLD, Files.readAllBytes(binary));
    }

    @Test
    void checkReportsWithoutDownloading() throws Exception {
        Path binary = installed(tempDir.resolve("bin"));

        Run r = update(binary, "--check");

        assertEquals(1, r.exit());
        assertTrue(r.out().contains("update available"), r.out());
        assertEquals(List.of(), downloads);
        assertArrayEquals(OLD, Files.readAllBytes(binary));
    }

    @Test
    void theCurrentVersionIsUpToDate() throws Exception {
        Path binary = installed(tempDir.resolve("bin"));
        latest.set("{\"tag_name\":\"" + CliVersion.CLI_VERSION + "\"}");

        Run r = update(binary);

        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("is up to date"), r.out());
        assertEquals(List.of(), downloads);
    }

    @Test
    void aHomebrewInstallIsLeftToBrew() throws Exception {
        Path binary = installed(tempDir.resolve("Cellar").resolve("floci").resolve("0.3.0").resolve("bin"));

        Run r = update(binary);

        assertEquals(1, r.exit());
        assertTrue(r.err().contains("brew upgrade floci"), r.err());
        assertArrayEquals(OLD, Files.readAllBytes(binary));
    }

    /** A captive portal or proxy page instead of the GitHub API must not become a download URL. */
    @Test
    void aLatestReleaseResponseWithoutAVersionIsRefused() throws Exception {
        Path binary = installed(tempDir.resolve("bin"));
        latest.set("<html>Sign in to the network</html>");

        Run r = update(binary);

        assertEquals(1, r.exit());
        assertTrue(r.err().contains("cannot determine the latest version"), r.err());
        assertEquals(List.of(), downloads);
    }
}
