package io.floci.cli.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** BL-026: a failed check is recorded too, so the next run does not retry and wait again. */
class UpdateNotifierTest {

    @TempDir
    Path home;

    private String realHome;

    @BeforeEach
    void pointHomeAtATempDir() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", realHome);
    }

    private Path cache;

    private void writeCache(Instant checkedAt, String version) throws Exception {
        cache = home.resolve(".floci").resolve("update-check.json");
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "{\"checkedAtEpochSeconds\":" + checkedAt.getEpochSecond()
                + ",\"latestVersion\":" + (version == null ? "null" : "\"" + version + "\"") + "}");
    }

    private UpdateCache readCache() throws Exception {
        return new ObjectMapper().readValue(cache.toFile(), UpdateCache.class);
    }

    private static boolean staleAt(UpdateCache entry, Instant when) {
        return Instant.ofEpochSecond(entry.checkedAtEpochSeconds()).plus(UpdateNotifier.TTL).isBefore(when);
    }

    // A port that was just bound and released: nothing listens there, so the fetch fails at once.
    private static String refusedUrl() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/releases/latest";
        }
    }

    @Test
    void aFailedCheckIsNotRetriedByTheNextRunAndKeepsTheLastKnownVersion() throws Exception {
        writeCache(Instant.now().minus(Duration.ofDays(3)), "0.2.0");

        UpdateNotifier.refreshInBackground(refusedUrl());
        UpdateNotifier.awaitRefresh(Duration.ofSeconds(5));

        UpdateCache written = readCache();
        assertEquals("0.2.0", written.latestVersion());
        assertFalse(staleAt(written, Instant.now()), "the next run does not check again");
        assertTrue(staleAt(written, Instant.now().plus(UpdateNotifier.RETRY_AFTER).plusSeconds(60)),
                "but an attempt is not a result: it is tried again after RETRY_AFTER, not a full day");
    }

    /**
     * The attempt is on record before the request is answered. A command that exits while the
     * request is still waiting never reaches a failure handler, and would otherwise check again,
     * and wait again, on every run.
     */
    @Test
    void anAttemptIsRecordedWhileTheRequestIsStillWaiting() throws Exception {
        writeCache(Instant.now().minus(Duration.ofDays(3)), "0.2.0");
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            UpdateNotifier.refreshInBackground("http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
            UpdateNotifier.awaitRefresh(Duration.ofMillis(250)); // what main() grants a quick command

            UpdateCache written = readCache();
            assertEquals("0.2.0", written.latestVersion());
            assertFalse(staleAt(written, Instant.now()), "recorded although the request has not come back");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    /** Another process finished a check in the meantime: its result stands and nothing is overwritten. */
    @Test
    void anAttemptDoesNotOverwriteAResultAnotherProcessJustWrote() throws Exception {
        writeCache(Instant.now(), "0.4.0");
        String before = Files.readString(cache);

        assertFalse(UpdateNotifier.recordAttempt());

        assertEquals(before, Files.readString(cache));
    }

    @Test
    void anAttemptOnAStaleCacheKeepsItsVersionAndAsksToGoOn() throws Exception {
        writeCache(Instant.now().minus(Duration.ofDays(3)), "0.2.0");

        assertTrue(UpdateNotifier.recordAttempt());

        assertEquals("0.2.0", readCache().latestVersion());
    }

    @Test
    void aSuccessfulCheckStoresTheVersionForAFullDay() throws Exception {
        writeCache(Instant.now().minus(Duration.ofDays(3)), "0.2.0");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"tag_name\":\"0.4.0\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            UpdateNotifier.refreshInBackground("http://127.0.0.1:" + server.getAddress().getPort() + "/latest");
            UpdateNotifier.awaitRefresh(Duration.ofSeconds(5));

            UpdateCache written = readCache();
            assertEquals("0.4.0", written.latestVersion());
            assertFalse(staleAt(written, Instant.now().plus(UpdateNotifier.RETRY_AFTER).plusSeconds(60)));
        } finally {
            server.stop(0);
        }
    }
}
