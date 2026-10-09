package io.floci.cli.unit;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.DoctorCommand;
import io.floci.cli.docker.CachingDockerClient;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** BL-028: doctor selects before running, survives a crashing check, and keeps its output order. */
class DoctorCommandTest {

    /** No docker subprocess: the endpoint lookup sees no container. */
    private static final DockerClient NO_DOCKER = new DockerClient() {
        @Override
        public Optional<ContainerInfo> inspectContainer(String name) {
            return Optional.empty();
        }
    };

    private record Named(String name, AtomicInteger runs, Check body) implements Check {
        Named(String name, Check body) {
            this(name, new AtomicInteger(), body);
        }

        @Override
        public CheckResult run(String endpoint, String container) {
            runs.incrementAndGet();
            return body.run(endpoint, container);
        }
    }

    private static Named ok(String name) {
        return new Named(name, (e, c) -> CheckResult.ok(name, "fine"));
    }

    private record Run(int exit, String out, String err) {}

    private static Run doctor(List<Check> checks, String... args) {
        CommandLine cmd = new CommandLine(new DoctorCommand(ProductProfile.AWS, NO_DOCKER, checks));
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            return new Run(cmd.execute(args), outBuf.toString(), errBuf.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    @Test
    void checkOptionRunsOnlyTheNamedCheck() {
        Named a = ok("a.check");
        Named b = ok("b.check");

        Run r = doctor(List.of(a, b), "--check", "b.check");

        assertEquals(0, r.exit(), r.err());
        assertEquals(0, a.runs().get());
        assertEquals(1, b.runs().get());
        assertTrue(r.out().contains("b.check"), r.out());
        assertFalse(r.out().contains("a.check"), r.out());
    }

    @Test
    void anUnknownCheckNameExitsTwoAndListsTheNames() {
        Run r = doctor(List.of(ok("a.check"), ok("b.check")), "--check", "nope");

        assertEquals(2, r.exit());
        assertTrue(r.err().contains("Unknown check 'nope'"), r.err());
        assertTrue(r.err().contains("a.check, b.check"), r.err());
    }

    @Test
    void aCrashingCheckIsReportedAndTheOthersStillRun() {
        Named boom = new Named("boom.check", (e, c) -> { throw new IllegalStateException("kaput"); });
        Named after = ok("after.check");

        Run r = doctor(List.of(boom, after), "-o", "json");

        assertEquals(1, after.runs().get());
        assertTrue(r.out().contains("\"boom.check\""), r.out());
        assertTrue(r.out().contains("check failed: kaput"), r.out());
        assertTrue(r.out().contains("\"after.check\""), r.out());
    }

    @Test
    void resultsKeepListOrderEvenWhenLaterChecksFinishFirst() {
        CountDownLatch secondDone = new CountDownLatch(1);
        Named slow = new Named("first.check", (e, c) -> {
            try {
                // Finishes only after the second check has completed.
                assertTrue(secondDone.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return CheckResult.ok("first.check", "slow");
        });
        Named fast = new Named("second.check", (e, c) -> {
            secondDone.countDown();
            return CheckResult.ok("second.check", "fast");
        });

        Run r = doctor(List.of(slow, fast));

        // Both checks must have succeeded: a timed-out latch would surface as "check failed".
        assertEquals(0, r.exit(), r.out() + r.err());
        assertFalse(r.out().contains("check failed"), r.out());
        assertTrue(r.out().indexOf("first.check") < r.out().indexOf("second.check"), r.out());
    }

    @Test
    void theCachingClientRunsEachLookupOnceAcrossThreads() throws Exception {
        AtomicInteger versionCalls = new AtomicInteger();
        AtomicInteger inspectCalls = new AtomicInteger();
        // Counts what reaches the delegate, i.e. what would spawn a docker subprocess.
        DockerClient counting = new DockerClient() {
            @Override
            public String dockerVersion() {
                versionCalls.incrementAndGet();
                sleepBriefly();
                return "27.0.0";
            }

            @Override
            public Optional<ContainerInfo> inspectContainer(String name) {
                inspectCalls.incrementAndGet();
                sleepBriefly();
                return Optional.empty();
            }
        };
        CachingDockerClient cache = new CachingDockerClient(counting);

        // Futures, not bare threads: an assertion failing on a worker must fail this test.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> workers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                workers.add(pool.submit(() -> {
                    assertEquals("27.0.0", cache.dockerVersion());
                    assertTrue(cache.inspectContainer("floci").isEmpty());
                    return null;
                }));
            }
            for (Future<?> worker : workers) worker.get();
        }

        assertEquals(1, versionCalls.get());
        assertEquals(1, inspectCalls.get());
    }

    @Test
    void theCachingClientRemembersFailuresToo() {
        AtomicInteger calls = new AtomicInteger();
        CachingDockerClient cache = new CachingDockerClient(new DockerClient() {
            @Override
            public String dockerVersion() throws DockerException {
                calls.incrementAndGet();
                throw new DockerException("daemon down");
            }
        });

        assertThrows(DockerException.class, cache::dockerVersion);
        assertThrows(DockerException.class, cache::dockerVersion);
        assertEquals(1, calls.get());
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** BL-036: -o json reports a failed check through the exit code, as text output does. */
    @Test
    void jsonOutputExitsOneWhenACheckFails() {
        Named failing = new Named("bad.check", (e, c) -> CheckResult.fail("bad.check", "broken", "fix it"));

        Run r = doctor(List.of(ok("a.check"), failing), "-o", "json");

        assertEquals(1, r.exit(), r.out());
        assertTrue(r.out().contains("\"bad.check\""), r.out());
    }

    /** BL-038: warnings alone are not reported as "0 issue(s) found". */
    @Test
    void warningsOnlyDoNotReadAsZeroIssues() {
        Named warning = new Named("warn.check", (e, c) -> CheckResult.warn("warn.check", "hmm", null));

        Run r = doctor(List.of(ok("a.check"), warning));

        assertEquals(0, r.exit());
        assertFalse(r.out().contains("0 issue(s)"), r.out());
        assertTrue(r.out().contains("1 warning(s) to review"), r.out());
    }
}
