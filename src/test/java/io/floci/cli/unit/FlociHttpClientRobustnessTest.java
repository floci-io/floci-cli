package io.floci.cli.unit;

import com.sun.net.httpserver.HttpServer;
import io.floci.cli.http.FlociException;
import io.floci.cli.http.FlociHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** BL-047: what FlociHttpClient sends, and how it reports responses that are not what it expects. */
class FlociHttpClientRobustnessTest {

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{\"version\":\"1.0\",\"services\":[]}");
    private final AtomicReference<String> location = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getRawPath());
            if (location.get() != null) exchange.getResponseHeaders().add("Location", location.get());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void everyTrailingSlashIsStripped() throws Exception {
        new FlociHttpClient(endpoint() + "///", "/_floci").health();

        assertEquals("/_floci/health", lastPath.get());
    }

    @Test
    void aBodyThatIsNotJsonIsReportedAsSuch() {
        body.set("OK");

        FlociException e = assertThrows(FlociException.class, () -> new FlociHttpClient(endpoint(), "/_floci").health());
        assertTrue(e.getMessage().contains("is not JSON"), e.getMessage());
    }

    @Test
    void aRedirectIsReportedNotFollowed() {
        status.set(302);
        body.set("");
        location.set("http://elsewhere.example/_floci/health");

        FlociException e = assertThrows(FlociException.class, () -> new FlociHttpClient(endpoint(), "/_floci").health());
        assertTrue(e.getMessage().contains("redirected"), e.getMessage());
        assertTrue(e.getMessage().contains("elsewhere.example"), e.getMessage());
    }

    @Test
    void snapshotNamesAreOnePathSegment() throws Exception {
        status.set(204);
        body.set("");

        new FlociHttpClient(endpoint(), "/_floci").deleteSnapshot("my snap?x");

        assertEquals("/_floci/snapshots/my%20snap%3Fx", lastPath.get());
    }

    @Test
    void snapshotNamesThatWouldChangeThePathAreRefused() {
        FlociHttpClient client = new FlociHttpClient(endpoint(), "/_floci");
        for (String bad : new String[]{"..", ".", "a/b", "../escape"}) {
            FlociException e = assertThrows(FlociException.class, () -> client.deleteSnapshot(bad), bad);
            assertTrue(e.getMessage().contains("Invalid snapshot name"), e.getMessage());
        }
        assertNull(lastPath.get(), "nothing reached the server");
    }
}
