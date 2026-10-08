package io.floci.cli.unit;

import io.floci.cli.azcli.CaBundle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;

import static org.junit.jupiter.api.Assertions.*;

class CaBundleTest {

    @Test
    void keepsThePublicRootsAndEndsWithTheFlociCa() throws Exception {
        String floci = AzTestCerts.somePem();

        String bundle = CaBundle.build(floci);

        // REQUESTS_CA_BUNDLE replaces Python's trust store, so the public roots must survive.
        int certs = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(bundle.getBytes(StandardCharsets.US_ASCII))).size();
        assertTrue(certs > 1, "expected JDK roots plus the floci CA, got " + certs);
        assertTrue(bundle.endsWith(floci.strip() + "\n"));
    }

    @Test
    void refusesSomethingThatIsNotACertificate() {
        assertThrows(Exception.class, () -> CaBundle.build("<html>not found</html>"));
    }
}
