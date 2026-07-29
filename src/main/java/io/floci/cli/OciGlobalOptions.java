package io.floci.cli;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.Option;

import java.net.URI;

public class OciGlobalOptions {

    /** Control-plane path prefix exposed by the floci-oci server (differs from AWS/Azure's {@code /_floci}). */
    public static final String CONTROL_PREFIX = "/_floci-oci";

    @Option(names = {"--endpoint"},
            description = "Floci OCI server endpoint URL",
            defaultValue = "${FLOCI_OCI_ENDPOINT:-http://localhost:4599}",
            paramLabel = "<url>")
    public String endpoint;

    @Option(names = {"--container"},
            description = "Floci OCI container name",
            defaultValue = "${FLOCI_OCI_CONTAINER:-floci-oci}",
            paramLabel = "<name>")
    public String container;

    @Option(names = {"--profile"},
            description = "Config profile from ~/.floci/profiles/",
            paramLabel = "<name>")
    public String profile;

    @Option(names = {"--output", "-o"},
            description = "Output format: text, json, yaml",
            defaultValue = "text",
            paramLabel = "text|json|yaml")
    public OutputFormat output;

    @Option(names = {"--quiet", "-q"}, description = "Suppress non-error output")
    public boolean quiet;

    @Option(names = {"--verbose", "-v"}, description = "Debug logging to stderr")
    public boolean verbose;

    @Option(names = {"--no-color"}, description = "Disable ANSI colors")
    public boolean noColor;

    public Printer printer() {
        if (noColor || System.console() == null) {
            Ansi.disable();
        }
        return new Printer(System.out, System.err, output, quiet);
    }

    public String resolvedEndpoint(DockerClient docker) {
        try {
            return docker.inspectContainer(container)
                    .map(info -> endpointFromPorts(info.ports(), endpoint))
                    .orElse(endpoint);
        } catch (Exception e) {
            return endpoint;
        }
    }

    public String endpointFromPorts(String ports, String fallback) {
        if (ports == null || ports.isBlank()) return fallback;
        try {
            int containerPort = URI.create(fallback).getPort();
            if (containerPort == -1) containerPort = 4599;
            for (String mapping : ports.trim().split("\\s+")) {
                int arrow = mapping.indexOf("->");
                if (arrow < 0) continue;
                String hostPort = mapping.substring(0, arrow);
                String rest = mapping.substring(arrow + 2);
                String cPort = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
                if (String.valueOf(containerPort).equals(cPort)) {
                    return "http://localhost:" + hostPort;
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }
}