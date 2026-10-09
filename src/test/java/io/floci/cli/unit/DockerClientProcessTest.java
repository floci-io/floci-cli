package io.floci.cli.unit;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
