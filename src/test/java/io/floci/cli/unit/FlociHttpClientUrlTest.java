package io.floci.cli.unit;

import com.sun.net.httpserver.HttpServer;
import io.floci.cli.http.FlociHttpClient;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Request paths go before an endpoint's query, not after it. */
class FlociHttpClientUrlTest {

    @Test
    void theHealthPathIsInsertedBeforeTheEndpointsQuery() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.set(exchange.getRequestURI().toString());
            byte[] body = "{\"version\":\"1.2.3\",\"services\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/?token=a%26b#frag";

            assertEquals("1.2.3", new FlociHttpClient(endpoint, "/_floci").health().version());
            assertEquals("/_floci/health?token=a%26b", seen.get());
        } finally {
            server.stop(0);
        }
    }
}
