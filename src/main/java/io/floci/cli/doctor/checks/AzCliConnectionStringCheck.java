package io.floci.cli.doctor.checks;

import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;
import io.floci.cli.output.ShellExport;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AzCliConnectionStringCheck implements Check {

    private static final Pattern ENDPOINT_PORT_PATTERN =
            Pattern.compile("BlobEndpoint=https?://[^:]+:(\\d+)/", Pattern.CASE_INSENSITIVE);

    @Override
    public CheckResult run(String endpoint, String container) {
        String connStr = System.getenv("AZURE_STORAGE_CONNECTION_STRING");
        int effectivePort = extractPort(endpoint);

        if (connStr != null && !connStr.isBlank()) {
            Matcher m = ENDPOINT_PORT_PATTERN.matcher(connStr);
            if (m.find()) {
                int connPort = Integer.parseInt(m.group(1));
                if (effectivePort != -1 && connPort != effectivePort) {
                    String corrected = connStr.replaceAll(":(\\d+)/", ":" + effectivePort + "/");
                    return CheckResult.warn("az.cli.connection-string",
                            "AZURE_STORAGE_CONNECTION_STRING port mismatch — floci-az is on port " + effectivePort,
                            ShellExport.formatExport("bash", "AZURE_STORAGE_CONNECTION_STRING", corrected));
                }
            }
            return CheckResult.ok("az.cli.connection-string", "AZURE_STORAGE_CONNECTION_STRING is set");
        }

        int port = effectivePort != -1 ? effectivePort : 4577;
        String suggested = buildConnectionString("devstoreaccount1", "localhost.floci.io", port);
        return CheckResult.warn("az.cli.connection-string",
                "AZURE_STORAGE_CONNECTION_STRING is not set",
                ShellExport.formatExport("bash", "AZURE_STORAGE_CONNECTION_STRING", suggested));
    }

    private int extractPort(String url) {
        try {
            int port = URI.create(url).getPort();
            return port == -1 ? 4577 : port;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String buildConnectionString(String account, String host, int port) {
        String base = "http://" + host + ":" + port + "/" + account;
        return "DefaultEndpointsProtocol=http" +
                ";AccountName=" + account +
                ";AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==" +
                ";BlobEndpoint=" + base +
                ";QueueEndpoint=" + base + "-queue" +
                ";TableEndpoint=" + base + "-table;";
    }
}
