package io.floci.cli.unit;

import com.sun.net.httpserver.HttpServer;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.http.TlsUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The three answers floci-az's /_floci/tls-cert gives, against a local JDK HTTP server. */
class FlociHttpClientTlsCertTest {

    private HttpServer server;

    private FlociHttpClient serving(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_floci/tls-cert", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new FlociHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), "/_floci");
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void returnsThePem() throws Exception {
        String pem = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n";

        assertEquals(pem, serving(200, pem).tlsCert());
    }

    @Test
    void tlsOffIsFinal() throws Exception {
        FlociHttpClient client = serving(404,
                "{\"tlsEnabled\":false,\"error\":\"NotFound\",\"message\":\"TLS is not enabled\"}");

        TlsUnavailableException e = assertThrows(TlsUnavailableException.class, client::tlsCert);
        assertFalse(e.notYet());
        assertEquals("TLS is not enabled", e.getMessage());
    }

    @Test
    void stillGeneratingIsWorthWaitingFor() throws Exception {
        FlociHttpClient client = serving(404,
                "{\"tlsEnabled\":true,\"error\":\"NotFound\",\"message\":\"certificate not available yet\"}");

        assertTrue(assertThrows(TlsUnavailableException.class, client::tlsCert).notYet());
    }

    @Test
    void aServerWithoutTheEndpointReadsAsTlsOff() throws Exception {
        FlociHttpClient client = serving(404, "<html>no such route</html>");

        assertFalse(assertThrows(TlsUnavailableException.class, client::tlsCert).notYet());
    }
}
