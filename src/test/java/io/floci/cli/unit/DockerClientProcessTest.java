package io.floci.cli.unit;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
