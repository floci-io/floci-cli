package io.floci.cli.unit;

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

import java.util.Optional;

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
    void endpointHintNamesTheProductTree() {
        // Port 9 refuses connections at once.
        CheckResult r = new EndpointReachableCheck(ProductProfile.OCI).run("http://127.0.0.1:9", "floci-oci");

        assertTrue(r.fix().contains("floci oci start"), r.fix());
    }

    @Test
    void portFallbackIsTheProductsDefaultPort() {
        assertEquals(4588, PortAvailableCheck.extractPort("not a url", ProductProfile.GCP.defaultPort()));
        assertEquals(4566, PortAvailableCheck.extractPort("not a url"));
    }

    @Test
    void httpClientErrorsNameTheProductTree() {
        FlociException e = assertThrows(FlociException.class,
                () -> new FlociHttpClient("http://127.0.0.1:9", ProductProfile.AZ).health());

        assertTrue(e.getMessage().contains("floci az start"), e.getMessage());
    }

    @Test
    void httpsWithoutAPortMeans443() {
        assertEquals(443, AwsCliEndpointCheck.extractPort("https://floci.example"));
        assertEquals(80, AwsCliEndpointCheck.extractPort("http://floci.example"));
        assertEquals(4566, AwsCliEndpointCheck.extractPort("https://floci.example:4566"));
    }
}
