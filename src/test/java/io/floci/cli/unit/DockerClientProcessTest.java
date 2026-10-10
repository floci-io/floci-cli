package io.floci.cli.unit;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** An abandoned docker call must not leave its process running after the CLI moves on. */
class DockerClientProcessTest {

    @TempDir
    Path tempDir;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anInterruptedCallKillsItsDockerProcess() throws Exception {
        Path pidFile = tempDir.resolve("pid");
        Path fakeDocker = tempDir.resolve("docker");
        Files.writeString(fakeDocker, "#!/bin/sh\necho $$ > '" + pidFile + "'\nexec sleep 30\n");
        assertTrue(fakeDocker.toFile().setExecutable(true));
        DockerClient docker = new DockerClient(fakeDocker.toString());

        Thread call = Thread.ofVirtual().start(() -> {
            try {
                docker.inspectContainer("floci");
            } catch (DockerException expected) {
                // "Interrupted"
            }
        });
        long pid = awaitPid(pidFile);

        call.interrupt();
        call.join(5_000);

        assertFalse(call.isAlive(), "the call should return once interrupted");
        long deadline = System.currentTimeMillis() + 3_000;
        while (isAlive(pid) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(isAlive(pid), "docker process " + pid + " outlived the interrupted call");
    }

    /** A stalled daemon probe is cut off at the time limit and reads as unreachable. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aStalledDaemonProbeReportsUnreachableAndIsKilled() throws Exception {
        Path pidFile = tempDir.resolve("pid");
        DockerClient docker = new DockerClient(fakeDocker("echo $$ > '" + pidFile + "'\nexec sleep 30\n"),
                Duration.ofMillis(500));

        long start = System.nanoTime();
        boolean reachable = docker.isDaemonReachable();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(reachable);
        assertTrue(elapsedMs < 3_000, "took " + elapsedMs + " ms");
        assertExits(awaitPid(pidFile), "the stalled probe is still running");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theDaemonProbeFollowsTheExitCode() throws Exception {
        assertTrue(new DockerClient(fakeDocker("echo some output\nexit 0\n")).isDaemonReachable());
        assertFalse(new DockerClient(fakeDocker("echo cannot connect >&2\nexit 1\n")).isDaemonReachable());
    }

    /** "stop --timeout -1" is docker's wait-indefinitely, so the CLI must not cut it short. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anIndefiniteStopIsNotTimeLimited() throws Exception {
        Path argsFile = tempDir.resolve("args");
        DockerClient docker = new DockerClient(
                fakeDocker("for a in \"$@\"; do echo \"$a\"; done > '" + argsFile + "'\nsleep 1.5\n"),
                Duration.ofMillis(300));

        docker.stopContainer("floci", -1);

        assertEquals(List.of("stop", "-t", "-1", "--", "floci"), Files.readAllLines(argsFile));
    }

    /** A bounded stop still gets a limit: the grace period plus the client's own. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aBoundedStopIsStillTimeLimited() throws Exception {
        DockerClient docker = new DockerClient(fakeDocker("exec sleep 30\n"), Duration.ofMillis(300));

        DockerException e = assertThrows(DockerException.class, () -> docker.stopContainer("floci", 0));

        assertTrue(e.getMessage().contains("did not finish within"), e.getMessage());
    }

    /** Streaming calls have no time limit, so interruption is their only way out: it must kill docker. */
    @ParameterizedTest
    @ValueSource(strings = {"logs", "pull"})
    @DisabledOnOs(OS.WINDOWS)
    void anInterruptedStreamingCallKillsItsDockerProcess(String call) throws Exception {
        Path pidFile = tempDir.resolve("pid");
        // Lines on both streams first, so both reader threads are mid-read when the interrupt lands.
        DockerClient docker = new DockerClient(
                fakeDocker("echo $$ > '" + pidFile + "'\necho out\necho err >&2\nexec sleep 30\n"));
        AtomicReference<String> failure = new AtomicReference<>();

        Thread streaming = Thread.ofPlatform().start(() -> {
            try {
                if ("logs".equals(call)) {
                    docker.streamLogs("floci", true, 0, null);
                } else {
                    docker.pull("floci/floci:latest", "always");
                }
            } catch (DockerException e) {
                failure.set(e.getMessage());
            }
        });
        long pid = awaitPid(pidFile);

        streaming.interrupt();
        streaming.join(5_000);

        assertFalse(streaming.isAlive(), "the streaming call should return once interrupted");
        assertEquals("Interrupted", failure.get());
        assertExits(pid, "docker process " + pid + " outlived the interrupted streaming call");
    }

    private String fakeDocker(String body) throws Exception {
        Path fakeDocker = Files.createTempFile(tempDir, "docker", "");
        Files.writeString(fakeDocker, "#!/bin/sh\n" + body);
        assertTrue(fakeDocker.toFile().setExecutable(true));
        return fakeDocker.toString();
    }

    private static void assertExits(long pid, String message) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000;
        while (isAlive(pid) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(isAlive(pid), message);
    }

    private static long awaitPid(Path pidFile) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(pidFile)) {
                String text = Files.readString(pidFile).trim();
                if (!text.isEmpty()) return Long.parseLong(text);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("fake docker never started");
    }

    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** BL-045: a stalled docker call is killed at the time limit instead of hanging the CLI. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aCallThatOutlivesItsTimeLimitIsKilledAndReported() throws Exception {
        Path pidFile = tempDir.resolve("pid");
        Path fakeDocker = tempDir.resolve("docker");
        Files.writeString(fakeDocker, "#!/bin/sh\necho $$ > '" + pidFile + "'\nexec sleep 30\n");
        assertTrue(fakeDocker.toFile().setExecutable(true));
        DockerClient docker = new DockerClient(fakeDocker.toString(), Duration.ofMillis(500));

        long start = System.nanoTime();
        DockerException e = assertThrows(DockerException.class, docker::dockerVersion);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 3_000, "took " + elapsedMs + " ms");
        assertTrue(e.getMessage().contains("did not finish within"), e.getMessage());
        long pid = awaitPid(pidFile);
        long deadline = System.currentTimeMillis() + 3_000;
        while (isAlive(pid) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(isAlive(pid), "the timed-out docker process is still running");
    }

    /** BL-045: names go after "--", so a name starting with '-' is never read as a flag. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void namesArePassedAfterTheEndOfOptionsMarker() throws Exception {
        Path argsFile = tempDir.resolve("args");
        Path fakeDocker = tempDir.resolve("docker");
        Files.writeString(fakeDocker, "#!/bin/sh\nfor a in \"$@\"; do echo \"$a\"; done > '" + argsFile + "'\n");
        assertTrue(fakeDocker.toFile().setExecutable(true));
        DockerClient docker = new DockerClient(fakeDocker.toString());

        docker.removeContainer("--rm-everything");
        List<String> args = Files.readAllLines(argsFile);

        assertEquals(List.of("rm", "--", "--rm-everything"), args);
    }
}
