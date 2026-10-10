package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;
import io.floci.cli.doctor.CheckStatus;
import io.floci.cli.docker.CachingDockerClient;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
        return dockerChecks(profile, new CachingDockerClient());
    }

    /**
     * As above, all asking one {@code docker}: with a {@link CachingDockerClient} the version,
     * container and image lookups several checks share run once per doctor run, not once per check.
     */
    public static List<Check> dockerChecks(ProductProfile profile, DockerClient docker) {
        return List.of(
                new DockerInstalledCheck(docker),
                new DockerDaemonCheck(docker),
                new DockerSocketCheck(),
                new DockerVersionCheck(docker),
                new PortAvailableCheck(docker, profile),
                new ImagePresentCheck(profile, docker),
                new ImageVersionCheck(profile, docker),
                new ContainerRunningCheck(docker, profile),
                new EndpointReachableCheck(profile)
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

    private final DockerClient docker;

    private final List<Check> allChecks;

    public DoctorCommand() {
        this(ProductProfile.AWS, AWS_COMPANION_CHECKS);
    }

    /** Shim constructor: base docker checks for the profile + product companions. */
    public DoctorCommand(ProductProfile profile, List<Check> companionChecks) {
        this(profile, companionChecks, new CachingDockerClient());
    }

    private DoctorCommand(ProductProfile profile, List<Check> companionChecks, DockerClient docker) {
        this(profile, docker, concat(dockerChecks(profile, docker), companionChecks));
    }

    /** Test seam: exactly these checks, and {@code docker} for the endpoint lookup. */
    public DoctorCommand(ProductProfile profile, DockerClient docker, List<Check> checks) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
        this.docker = docker;
        this.allChecks = List.copyOf(checks);
    }

    private static List<Check> concat(List<Check> first, List<Check> second) {
        List<Check> checks = new ArrayList<>(first);
        checks.addAll(second);
        return checks;
    }

    public List<Check> allChecks() {
        return allChecks;
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();
        List<Check> selected = selected();
        if (selected.isEmpty()) {
            printer.error("Unknown check '" + checkName + "'.\nRun '" + profile.commandPrefix()
                    + " doctor' to see every check, or pass one of: " + String.join(", ", knownNames()) + ".");
            return ExitCode.USAGE;
        }

        List<CheckResult> results = new ArrayList<>();
        String effectiveEndpoint = global.resolvedEndpoint(docker);

        boolean textMode = printer.format() == OutputFormat.text;

        if (textMode) {
            printer.println(Ansi.bold(profile.displayName() + " Doctor") + " — checking your environment");
            printer.println("");
        }

        // The checks are independent and mostly wait on docker or the network, so they run
        // concurrently; results are still printed in list order as each one becomes available.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<CheckResult>> pending = selected.stream()
                    .map(check -> pool.submit(() -> runSafely(check, effectiveEndpoint, global.container)))
                    .toList();
            for (int i = 0; i < selected.size(); i++) {
                CheckResult result = await(selected.get(i), pending.get(i));
                // A check without a declared name is only known to match once it has run.
                if (checkName != null && !result.name().equals(checkName)) continue;
                results.add(result);
                if (textMode) printResult(printer, result);
            }
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
            // Same contract as text output: scripts read the exit code, whatever the format.
            return results.stream().anyMatch(r -> r.status() == CheckStatus.fail) ? 1 : 0;
        }

        long fails = results.stream().filter(r -> r.status() == CheckStatus.fail).count();
        long warns = results.stream().filter(r -> r.status() == CheckStatus.warn).count();

        printer.println("");
        if (fails == 0 && warns == 0) {
            printer.println(Ansi.green("All checks passed."));
        } else if (fails == 0) {
            // Nothing failed: say so, rather than "0 issue(s) found" next to the warnings.
            printer.println(Ansi.green("No failures") + ", " + warns + " warning(s) to review.");
        } else {
            printer.println(fails + " issue(s) found (" + fails + " fail, " + warns + " warn)."
                    + (fix ? "" : " Run with --fix to auto-resolve fixable issues."));
        }

        return fails > 0 ? 1 : 0;
    }

    // --check selects by declared name before anything runs; undeclared names are run and
    // filtered by their result, as before.
    private List<Check> selected() {
        if (checkName == null) return allChecks;
        return allChecks.stream()
                .filter(c -> c.name() == null || c.name().equals(checkName))
                .toList();
    }

    private List<String> knownNames() {
        return allChecks.stream().map(Check::name).filter(n -> n != null).toList();
    }

    // One failing check must not abort the run: report it and keep going.
    private static CheckResult runSafely(Check check, String endpoint, String container) {
        try {
            return check.run(endpoint, container);
        } catch (RuntimeException e) {
            return failed(check, e);
        }
    }

    private static CheckResult await(Check check, Future<CheckResult> result) {
        try {
            return result.get();
        } catch (ExecutionException e) {
            return failed(check, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(check, e);
        }
    }

    private static CheckResult failed(Check check, Throwable cause) {
        String name = check.name() != null ? check.name() : check.getClass().getSimpleName();
        return CheckResult.fail(name, "check failed: " + cause.getMessage(),
                "Report it at https://github.com/floci-io/floci-cli/issues if it persists.");
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
