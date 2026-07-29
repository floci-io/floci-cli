package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;
import io.floci.cli.doctor.CheckStatus;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.doctor.checks.AwsCliEndpointCheck;
import io.floci.cli.doctor.checks.AwsCliS3PathStyleCheck;
import io.floci.cli.doctor.checks.AzCliConnectionStringCheck;
import io.floci.cli.doctor.checks.AzCliInstalledCheck;
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
        description = "Run environment diagnostics for Floci AWS",
        mixinStandardHelpOptions = true
)
public class DoctorCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    @Option(names = {"--check"}, description = "Run only a specific check by name", paramLabel = "<name>")
    String checkName;

    @Option(names = {"--fix"}, description = "Attempt to auto-fix fixable issues")
    boolean fix;

    /** The base environment checks every product runs, in order: Docker first, then server. */
    public static List<Check> dockerChecks(ProductProfile profile) {
        return List.of(
                new DockerInstalledCheck(),
                new DockerDaemonCheck(),
                new DockerSocketCheck(),
                new DockerVersionCheck(),
                new PortAvailableCheck(),
                new ImagePresentCheck(profile.image()),
                new ImageVersionCheck(profile.image()),
                new ContainerRunningCheck(),
                new EndpointReachableCheck(profile.controlPrefix())
        );
    }

    public static final List<Check> AWS_COMPANION_CHECKS = List.of(
            new AwsCliEndpointCheck(),
            new AwsCliS3PathStyleCheck()
    );

    public static final List<Check> AZ_COMPANION_CHECKS = List.of(
            new AzCliInstalledCheck(),
            new AzCliConnectionStringCheck()
    );

    private final List<Check> allChecks;

    public DoctorCommand() {
        this(ProductProfile.AWS, AWS_COMPANION_CHECKS);
    }

    /** Test seam and shim constructor: base docker checks for the profile + product companions. */
    public DoctorCommand(ProductProfile profile, List<Check> companionChecks) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
        List<Check> checks = new ArrayList<>(dockerChecks(profile));
        checks.addAll(companionChecks);
        this.allChecks = List.copyOf(checks);
    }

    public List<Check> allChecks() {
        return allChecks;
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();
        List<CheckResult> results = new ArrayList<>();
        String effectiveEndpoint = global.resolvedEndpoint(new DockerClient());

        boolean textMode = printer.format() == OutputFormat.text;

        if (textMode) {
            printer.println(Ansi.bold(profile.displayName() + " Doctor") + " — checking your environment");
            printer.println("");
        }

        for (Check check : allChecks) {
            CheckResult result = check.run(effectiveEndpoint, global.container);
            if (checkName != null && !result.name().equals(checkName)) continue;
            results.add(result);
            if (textMode) printResult(printer, result);
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

    public static void printResult(Printer printer, CheckResult r) {
        String icon = switch (r.status()) {
            case ok   -> Ansi.green("✓");
            case warn -> Ansi.yellow("⚠");
            case fail -> Ansi.red("✗");
        };
        String namePadded = String.format("%-26s", r.name());
        printer.println("  " + icon + " " + namePadded + " " + r.message());
        if (r.fix() != null) {
            printer.println("                               " + Ansi.gray("Fix: " + r.fix()));
        }
    }
}
