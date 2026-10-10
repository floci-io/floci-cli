package io.floci.cli.unit;

import com.sun.net.httpserver.HttpServer;
import io.floci.cli.ProductProfile;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.doctor.CheckResult;
import io.floci.cli.doctor.checks.AwsCliEndpointCheck;
import io.floci.cli.doctor.checks.ContainerRunningCheck;
import io.floci.cli.doctor.checks.EndpointReachableCheck;
import io.floci.cli.doctor.checks.PortAvailableCheck;
import io.floci.cli.http.FlociException;
import io.floci.cli.http.FlociHttpClient;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** BL-037/BL-038: doctor's hints and fallbacks belong to the product being diagnosed. */
class DoctorHintsTest {

    private static final DockerClient NO_CONTAINER = new DockerClient() {
        @Override
        public Optional<ContainerInfo> inspectContainer(String name) {
            return Optional.empty();
        }
    };

    @Test
    void containerHintNamesTheProductTree() {
        CheckResult r = new ContainerRunningCheck(NO_CONTAINER, ProductProfile.GCP).run("http://localhost:4588", "floci-gcp");

        assertEquals("floci gcp start", r.fix());
    }

    @Test
    void endpointHintNamesTheProductTree() throws Exception {
        // A server of our own that answers 500: the check fails without depending on what the
        // machine happens to run on some port.
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            CheckResult r = new EndpointReachableCheck(ProductProfile.OCI)
                    .run("http://127.0.0.1:" + server.getAddress().getPort(), "floci-oci");

            assertTrue(r.fix().contains("floci oci start"), r.fix());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void portFallbackIsTheProductsDefaultPort() {
        assertEquals(4588, PortAvailableCheck.extractPort("not a url", ProductProfile.GCP.defaultPort()));
        assertEquals(4566, PortAvailableCheck.extractPort("not a url"));
    }

    @Test
    void httpClientErrorsNameTheProductTree() throws Exception {
        // A port that was just bound and released: nothing listens there, so the connection is refused.
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }

        FlociException e = assertThrows(FlociException.class,
                () -> new FlociHttpClient("http://127.0.0.1:" + closed, ProductProfile.AZ).health());

        assertTrue(e.getMessage().contains("Connection refused"), e.getMessage());
        assertTrue(e.getMessage().contains("floci az start"), e.getMessage());
    }

    /** A request that times out names the product's own status command, like the refused one. */
    @Test
    void httpClientTimeoutsNameTheProductTree() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                release.await(10, TimeUnit.SECONDS); // held until the client has given up
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            FlociHttpClient client = new FlociHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), ProductProfile.GCP);

            FlociException e = assertThrows(FlociException.class, () -> client.health(Duration.ofMillis(200)));

            assertTrue(e.getMessage().contains("floci gcp status"), e.getMessage());
            assertFalse(e.getMessage().contains("'floci status'"), e.getMessage());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void httpsWithoutAPortMeans443() {
        assertEquals(443, AwsCliEndpointCheck.extractPort("https://floci.example"));
        assertEquals(80, AwsCliEndpointCheck.extractPort("http://floci.example"));
        assertEquals(4566, AwsCliEndpointCheck.extractPort("https://floci.example:4566"));
    }
}
