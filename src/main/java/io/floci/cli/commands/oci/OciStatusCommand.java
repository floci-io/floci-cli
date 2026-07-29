package io.floci.cli.commands.oci;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "status",
        description = "Show Floci OCI container and server status",
        mixinStandardHelpOptions = true
)
public class OciStatusCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        DockerClient docker = new DockerClient();

        String containerState = "not found";
        String containerImage = "";
        String containerPorts = "";
        String effectiveEndpoint = global.endpoint;
        try {
            Optional<DockerClient.ContainerInfo> info = docker.inspectContainer(global.container);
            if (info.isPresent()) {
                containerState = info.get().state();
                containerImage = info.get().image();
                containerPorts = info.get().ports();
                effectiveEndpoint = global.endpointFromPorts(containerPorts, global.endpoint);
            }
        } catch (DockerException e) {
            containerState = "error: " + e.getMessage();
        }

        String serverVersion = "unavailable";
        String serverEdition = "";
        boolean reachable = false;
        FlociHttpClient client = new FlociHttpClient(effectiveEndpoint, OciGlobalOptions.CONTROL_PREFIX);
        try {
            var health = client.health();
            serverVersion = health.version();
            serverEdition = health.edition();
            reachable = true;
        } catch (Exception ignored) {}

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("container", global.container);
        data.put("state", containerState);
        data.put("image", containerImage);
        data.put("ports", containerPorts);
        data.put("endpoint", effectiveEndpoint);
        data.put("reachable", reachable);
        data.put("version", serverVersion);
        data.put("edition", serverEdition);

        if (printer.format() != OutputFormat.text) {
            printer.structured(data);
            return 0;
        }

        printer.println(Ansi.bold("Floci OCI Status"));
        printer.println("");
        String stateColor = switch (containerState) {
            case "running"   -> Ansi.green(containerState);
            case "not found" -> Ansi.gray(containerState);
            default          -> Ansi.red(containerState);
        };
        printer.println("  Container:  " + global.container + "  " + stateColor);
        if (!containerImage.isBlank()) printer.println("  Image:      " + containerImage);
        if (!containerPorts.isBlank()) printer.println("  Ports:      " + containerPorts);
        printer.println("  Endpoint:   " + effectiveEndpoint);
        printer.println("  Reachable:  " + (reachable ? Ansi.green("yes") : Ansi.red("no")));
        if (reachable) {
            printer.println("  Version:    " + serverVersion);
            if (!serverEdition.isBlank()) printer.println("  Edition:    " + serverEdition);
        }

        if (!reachable && !"running".equals(containerState)) {
            printer.println("");
            printer.println(Ansi.gray("Run 'floci oci start' to launch Floci OCI."));
        }

        return 0;
    }
}