package io.floci.cli.unit;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** A real certificate to stand in for the floci-az CA: the JDK cannot mint one without a library. */
final class AzTestCerts {

    private AzTestCerts() {
    }

    static String somePem() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        X509Certificate cert = ((X509TrustManager) tmf.getTrustManagers()[0]).getAcceptedIssuers()[0];
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }
}
