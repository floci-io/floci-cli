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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.regex.Pattern;

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
    private static final String SUMS_PATH = "/download/" + NEWER + "/sha256sums.txt";
    private static final Pattern ASSET_PATH =
            Pattern.compile("/download/" + Pattern.quote(NEWER) + "/floci-(darwin|linux)-(arm64|amd64)");

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
            // Like the real release host: only this release's own files exist.
            if (path.equals(SUMS_PATH)) {
                StringBuilder sums = new StringBuilder();
                for (String platform : List.of("darwin-arm64", "darwin-amd64", "linux-arm64", "linux-amd64")) {
                    sums.append(checksum.get()).append("  ./floci-").append(platform).append("/floci-").append(platform).append('\n');
                }
                respond(exchange, sums.toString().getBytes(StandardCharsets.UTF_8));
            } else if (ASSET_PATH.matcher(path).matches()) {
                respond(exchange, FRESH);
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
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
        // Exactly the release's binary for this platform, then its checksums: nothing else.
        assertEquals(2, downloads.size(), downloads.toString());
        assertTrue(ASSET_PATH.matcher(downloads.get(0)).matches(), downloads.toString());
        assertEquals(SUMS_PATH, downloads.get(1));
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

    /**
     * A captive portal or proxy page, or JSON without a usable release version, must not become a
     * download URL. The JSON cases reach the version check itself, not just the parser.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "<html>Sign in to the network</html>",
            "{}",
            "{\"tag_name\":\"\"}",
            "{\"tag_name\":\"not-a-version\"}",
            "{\"tag_name\":\"../../evil\"}",
            "{\"message\":\"API rate limit exceeded\"}"})
    void aLatestReleaseResponseWithoutAVersionIsRefused(String response) throws Exception {
        Path binary = installed(tempDir.resolve("bin"));
        latest.set(response);

        Run r = update(binary);

        assertEquals(1, r.exit());
        assertTrue(r.err().contains("cannot determine the latest version"), r.err());
        assertEquals(List.of(), downloads);
        assertArrayEquals(OLD, Files.readAllBytes(binary));
    }

    /** A release whose files are not on the download host fails the update and leaves the binary alone. */
    @Test
    void aReleaseWithoutItsFilesLeavesTheBinaryAlone() throws Exception {
        Path binary = installed(tempDir.resolve("bin"));
        latest.set("{\"tag_name\":\"98.0.0\"}"); // newer than this build, but the stub has no such release

        Run r = update(binary);

        assertEquals(1, r.exit());
        assertArrayEquals(OLD, Files.readAllBytes(binary));
        assertEquals(1, downloads.size(), downloads.toString());
        assertTrue(downloads.get(0).startsWith("/download/98.0.0/floci-"), downloads.toString());
    }

    /** --check tells "the check failed" (2) from "an update is available" (1). */
    @Test
    void aCheckThatCannotBeMadeExitsTwo() throws Exception {
        Path binary = installed(tempDir.resolve("bin"));
        latest.set("{}");

        Run r = update(binary, "--check");

        assertEquals(2, r.exit());
        assertTrue(r.err().contains("cannot determine the latest version"), r.err());
        assertEquals(List.of(), downloads);
    }

    /** The whole update through a symlinked install: the real file is replaced, the link stays a link. */
    @Test
    void anInstallBehindASymlinkIsUpdatedInPlace() throws Exception {
        Path real = installed(tempDir.resolve("opt"));
        Path link = Files.createSymbolicLink(Files.createDirectories(tempDir.resolve("bin")).resolve("floci"), real);

        Run r = update(link);

        assertEquals(0, r.exit(), r.err());
        assertTrue(Files.isSymbolicLink(link), "the link is still a link");
        assertArrayEquals(FRESH, Files.readAllBytes(real));
        try (var left = Files.list(real.getParent())) {
            assertEquals(List.of("floci"), left.map(p -> p.getFileName().toString()).toList(), "no staging file left behind");
        }
    }
}
