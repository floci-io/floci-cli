package io.floci.cli.commands.oci;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.commands.DoctorCommand;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;
import io.floci.cli.doctor.CheckStatus;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.doctor.checks.ContainerRunningCheck;
import io.floci.cli.doctor.checks.DockerDaemonCheck;
import io.floci.cli.doctor.checks.DockerInstalledCheck;
import io.floci.cli.doctor.checks.DockerSocketCheck;
import io.floci.cli.doctor.checks.DockerVersionCheck;
import io.floci.cli.doctor.checks.EndpointReachableCheck;
import io.floci.cli.doctor.checks.ImagePresentCheck;
import io.floci.cli.doctor.checks.ImageVersionCheck;
import io.floci.cli.doctor.checks.PortAvailableCheck;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "doctor",
        description = "Run environment diagnostics for Floci OCI",
        mixinStandardHelpOptions = true
)
public class OciDoctorCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Option(names = {"--check"}, description = "Run only a specific check by name", paramLabel = "<name>")
    String checkName;

    @Option(names = {"--fix"}, description = "Attempt to auto-fix fixable issues")
    boolean fix;

    private static final List<Check> ALL_CHECKS;

    static {
        List<Check> checks = new ArrayList<>();
        checks.add(new DockerInstalledCheck());
        checks.add(new DockerDaemonCheck());
        checks.add(new DockerSocketCheck());
        checks.add(new DockerVersionCheck());
        checks.add(new PortAvailableCheck());
        checks.add(new ImagePresentCheck("floci/floci-oci"));
        checks.add(new ImageVersionCheck("floci/floci-oci"));
        checks.add(new ContainerRunningCheck());
        checks.add(new EndpointReachableCheck(io.floci.cli.OciGlobalOptions.CONTROL_PREFIX));
        ALL_CHECKS = List.copyOf(checks);
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();
        List<CheckResult> results = new ArrayList<>();
        String effectiveEndpoint = global.resolvedEndpoint(new DockerClient());

        boolean textMode = printer.format() == OutputFormat.text;

        if (textMode) {
            printer.println(Ansi.bold("Floci OCI Doctor") + " — checking your environment");
            printer.println("");
        }

        for (Check check : ALL_CHECKS) {
            CheckResult result = check.run(effectiveEndpoint, global.container);
            if (checkName != null && !result.name().equals(checkName)) continue;
            results.add(result);
            if (textMode) DoctorCommand.printResult(printer, result);
        }

        if (!textMode) {
            List<Map<String, Object>> structured = new ArrayList<>();
            for (CheckResult r : results) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", r.name());
                m.put("status", r.status().name());
                m.put("message", r.message());
                if (r.fix() != null) m.put("fix", r.fix());
                structured.add(m);
            }
            printer.structured(structured);
            return 0;
        }

        long fails = results.stream().filter(r -> r.status() == CheckStatus.fail).count();
        long warns = results.stream().filter(r -> r.status() == CheckStatus.warn).count();

        printer.println("");
        if (fails == 0 && warns == 0) {
            printer.println(Ansi.green("All checks passed."));
        } else {
            printer.println(fails + " issue(s) found (" + fails + " fail, " + warns + " warn)."
                    + (fix ? "" : " Run with --fix to auto-resolve fixable issues."));
        }

        return fails > 0 ? 1 : 0;
    }
}
