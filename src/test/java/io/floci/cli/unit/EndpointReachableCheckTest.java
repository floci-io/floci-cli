package io.floci.cli.unit;

import com.sun.net.httpserver.HttpServer;
import io.floci.cli.doctor.CheckResult;
import io.floci.cli.doctor.CheckStatus;
import io.floci.cli.doctor.checks.EndpointReachableCheck;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EndpointReachableCheckTest {

    private static CheckResult checkAgainst(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_floci/health", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            return new EndpointReachableCheck("/_floci")
                    .run("http://127.0.0.1:" + server.getAddress().getPort(), "floci");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsTheServerVersionFromItsHealthDocument() throws Exception {
        CheckResult r = checkAgainst(200, "{\"version\":\"9.9.9\",\"services\":[]}");

        assertEquals(CheckStatus.ok, r.status());
        assertTrue(r.message().contains("v9.9.9"), r.message());
    }

    /** A 200 with a body that is not JSON is still a reachable server. */
    @Test
    void aReachableServerWithAnUnreadableBodyStillPasses() throws Exception {
        CheckResult r = checkAgainst(200, "OK");

        assertEquals(CheckStatus.ok, r.status(), r.message());
    }

    @Test
    void anErrorStatusFails() throws Exception {
        assertEquals(CheckStatus.fail, checkAgainst(503, "down").status());
    }
}
