package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.http.FlociHttpClient;
import io.floci.cli.util.Durations;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;

import java.util.Map;
import picocli.CommandLine.*;

import java.io.Console;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

@Command(
        name = "wait",
        description = "Wait until Floci AWS is ready to accept requests",
        mixinStandardHelpOptions = true
)
public class WaitCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public WaitCommand() {
        this(ProductProfile.AWS);
    }

    protected WaitCommand(ProductProfile profile) {
        this(profile, new DockerClient());
    }

    /** Test seam: {@code docker} answers the container lookup that finds the endpoint. */
    public WaitCommand(ProductProfile profile, DockerClient docker) {
        this(profile, docker, WaitCommand::interactive);
    }

    /** Test seam: {@code terminal} stands in for "stdout is a terminal", which a test run is not. */
    public WaitCommand(ProductProfile profile, DockerClient docker, BooleanSupplier terminal) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
        this.docker = docker;
        this.terminal = terminal;
    }

    private final DockerClient docker;
    private final BooleanSupplier terminal;

    @Option(names = {"--timeout"}, description = "Maximum time to wait (e.g. 30s, 2m)", defaultValue = "30s", paramLabel = "<duration>")
    String timeout;

    @Option(names = {"--service"}, description = "Wait until a specific service is enabled", paramLabel = "<name>")
    String service;

    /**
     * Set by {@code start}, which already knows the port it bound: skips the {@code docker inspect}
     * that resolving the endpoint from the container would cost.
     */
    String knownEndpoint;

    static final long FIRST_DELAY_MS = 25;
    static final long MAX_DELAY_MS = 500;
    static final Duration MAX_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @Override
    public Integer call() {
        Printer printer = global.printer();
        long timeoutMillis;
        try {
            timeoutMillis = Durations.parseDuration(timeout);
        } catch (IllegalArgumentException e) {
            printer.error(e.getMessage());
            return 2;
        }
        // Only a person at a terminal watching text output gets the spinner: in -o json/yaml or
        // in a pipe its frames would land in the data.
        boolean spinner = printer.format() == OutputFormat.text && terminal.getAsBoolean();
        // The deadline starts now, so the container lookup below counts against --timeout too.
        Instant deadline = Instant.now().plusMillis(timeoutMillis);
        String effectiveEndpoint = knownEndpoint != null ? knownEndpoint : endpointWithin(deadline);
        FlociHttpClient client = new FlociHttpClient(effectiveEndpoint, profile.controlPrefix());

        // Poll soon after a start, then back off; never let a request or a pause run past the
        // deadline, so --timeout is a real upper bound.
        long delay = FIRST_DELAY_MS;
        while (Instant.now().isBefore(deadline)) {
            if (isReady(client, service, requestTimeout(Duration.between(Instant.now(), deadline)))) {
                if (spinner) clearSpinner(printer);
                if (printer.format() != OutputFormat.text) {
                    printer.structured(Map.of("ready", true, "endpoint", effectiveEndpoint));
                } else {
                    printer.println(Ansi.green(profile.displayName() + " is ready") + " (" + effectiveEndpoint + ")");
                }
                return 0;
            }
            if (spinner) printSpinner(printer, deadline);
            long remaining = Duration.between(Instant.now(), deadline).toMillis();
            if (remaining <= 0) break;
            try { Thread.sleep(Math.min(delay, remaining)); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            delay = nextDelay(delay);
        }

        if (spinner) clearSpinner(printer);
        printer.error("Timed out waiting for " + profile.displayName() + " after " + timeout
                + ".\nIs the container running? Try '" + profile.commandPrefix() + " status'.");
        return 1;
    }

    // A stalled daemon or an unreachable remote DOCKER_HOST can hold `docker inspect` far longer
    // than --timeout, so the lookup gets the time left and the configured endpoint is the fallback.
    private String endpointWithin(Instant deadline) {
        long remaining = Duration.between(Instant.now(), deadline).toMillis();
        if (remaining <= 0) return global.endpoint;
        // A detached virtual thread: a hung lookup is abandoned, not joined, and cannot keep the
        // JVM alive once wait returns.
        CompletableFuture<String> resolved = new CompletableFuture<>();
        Thread lookup = Thread.ofVirtual().name("floci-wait-endpoint").start(() -> {
            try {
                resolved.complete(global.resolvedEndpoint(docker));
            } catch (RuntimeException e) {
                resolved.complete(global.endpoint);
            }
        });
        try {
            return resolved.get(remaining, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            lookup.interrupt(); // DockerClient kills the docker process it was waiting on
            return global.endpoint;
        } catch (InterruptedException e) {
            lookup.interrupt();
            Thread.currentThread().interrupt();
            return global.endpoint;
        }
    }

    /** 25 ms, 50, 200, 400, then 500 ms between polls: an emulator is often up within
     * a few dozen milliseconds of `docker run`, and a refused local poll costs nothing. */
    public static long nextDelay(long previous) {
        return Math.min(previous * 2, MAX_DELAY_MS);
    }

    /** A request may use the time left, up to the usual 10 s, and at least a millisecond. */
    public static Duration requestTimeout(Duration remaining) {
        if (remaining.compareTo(MAX_REQUEST_TIMEOUT) > 0) return MAX_REQUEST_TIMEOUT;
        return remaining.isNegative() || remaining.isZero() ? Duration.ofMillis(1) : remaining;
    }

    private boolean isReady(FlociHttpClient client, String requiredService, Duration timeout) {
        try {
            var health = client.health(timeout);
            if (requiredService == null) return true;
            for (String s : health.services()) {
                if (s.equalsIgnoreCase(requiredService)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean interactive() {
        Console console = System.console();
        return console != null && console.isTerminal();
    }

    private static void clearSpinner(Printer printer) {
        printer.print("\r" + " ".repeat(40) + "\r");
    }

    private void printSpinner(Printer printer, Instant deadline) {
        long remaining = Duration.between(Instant.now(), deadline).toSeconds();
        printer.print("\r" + Ansi.gray("Waiting... (" + remaining + "s remaining)") + "   ");
    }

}
