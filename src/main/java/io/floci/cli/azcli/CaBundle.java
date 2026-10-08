package io.floci.cli.azcli;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Builds the PEM bundle the az CLI is pointed at through {@code REQUESTS_CA_BUNDLE}.
 *
 * <p>That variable replaces the trust store of az's Python runtime rather than adding to it, so a
 * bundle holding only the floci-az CA would break every other HTTPS call az (or anything else in
 * the same shell) makes. The bundle is the JDK's default trust anchors followed by the floci-az CA,
 * which needs no Python and no certifi lookup.
 */
public final class CaBundle {

    private CaBundle() {
    }

    /** The JDK default trust anchors, PEM encoded, then {@code flociCaPem}. */
    public static String build(String flociCaPem) throws Exception {
        parse(flociCaPem); // fail here, not inside az, on a malformed certificate
        StringBuilder sb = new StringBuilder();
        for (X509Certificate cert : defaultTrustAnchors()) {
            sb.append(toPem(cert));
        }
        sb.append(flociCaPem.strip()).append('\n');
        return sb.toString();
    }

    static X509Certificate[] defaultTrustAnchors() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) {
                return x509.getAcceptedIssuers();
            }
        }
        return new X509Certificate[0];
    }

    static X509Certificate parse(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String toPem(X509Certificate cert) throws Exception {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
    }
}
