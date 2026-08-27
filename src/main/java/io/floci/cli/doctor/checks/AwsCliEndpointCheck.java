package io.floci.cli.doctor.checks;

import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;
import io.floci.cli.output.ShellExport;

import java.net.URI;
import java.net.URISyntaxException;

public class AwsCliEndpointCheck implements Check {

    @Override
    public CheckResult run(String endpoint, String container) {
        if (!isAwsCliInstalled()) {
            return CheckResult.ok("aws.cli.endpoint", "aws CLI not installed — skipped");
        }
        String envVar = System.getenv("AWS_ENDPOINT_URL");
        int effectivePort = extractPort(endpoint);
        if (envVar != null && !envVar.isBlank()) {
            int envPort = extractPort(envVar);
            if (envPort != -1 && effectivePort != -1 && envPort != effectivePort) {
                String corrected = replacePort(envVar, effectivePort);
                return CheckResult.warn("aws.cli.endpoint",
                        "AWS_ENDPOINT_URL=" + envVar + " (port mismatch — Floci is on port " + effectivePort + ")",
                        ShellExport.formatExport("bash", "AWS_ENDPOINT_URL", corrected));
            }
            return CheckResult.ok("aws.cli.endpoint", "AWS_ENDPOINT_URL=" + envVar);
        }
        String suggested = "http://localhost.floci.io:" + (effectivePort != -1 ? effectivePort : 4566);
        return CheckResult.warn("aws.cli.endpoint",
                "AWS_ENDPOINT_URL is not set",
                ShellExport.formatExport("bash", "AWS_ENDPOINT_URL", suggested));
    }

    private int extractPort(String url) {
        try {
            int port = URI.create(url).getPort();
            return port == -1 ? 80 : port;
        } catch (Exception e) {
            return -1;
        }
    }

    private String replacePort(String url, int newPort) {
        try {
            URI uri = URI.create(url);
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(),
                    newPort, uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
        } catch (Exception e) {
            return url;
        }
    }

    private boolean isAwsCliInstalled() {
        try {
            Process p = new ProcessBuilder("aws", "--version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
