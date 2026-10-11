package io.floci.cli.unit;

import io.floci.cli.commands.UpdateCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** BL-049: which release asset floci update picks for the machine it runs on. */
class UpdatePlatformTest {

    @Test
    void picksTheAssetForEachPublishedPlatform() {
        assertEquals("darwin-arm64", UpdateCommand.detectPlatform("Mac OS X", "aarch64", () -> true));
        assertEquals("linux-amd64", UpdateCommand.detectPlatform("Linux", "amd64", () -> false));
        assertEquals("linux-arm64", UpdateCommand.detectPlatform("Linux", "aarch64", () -> false));
    }

    /** An x86 build under Rosetta must move to the arm64 build, not stay x86 forever. */
    @Test
    void anX86BuildOnAppleSiliconUpdatesToArm64() {
        assertEquals("darwin-arm64", UpdateCommand.detectPlatform("Mac OS X", "x86_64", () -> true));
        assertEquals("darwin-amd64", UpdateCommand.detectPlatform("Mac OS X", "x86_64", () -> false));
    }

    @Test
    void windowsPointsAtTheInstaller() {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> UpdateCommand.detectPlatform("Windows 11", "amd64", () -> false));
        assertTrue(e.getMessage().contains("install.ps1"), e.getMessage());
    }

    /** An invalid --version is refused before it reaches a download URL; --check reports it as 2. */
    @Test
    void anInvalidVersionIsAFailedCheck() {
        PrintStream err = System.err;
        PrintStream out = System.out;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuf));
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        int exit;
        try {
            exit = new CommandLine(new UpdateCommand()).execute("--check", "--version", "../../evil");
        } finally {
            System.setErr(err);
            System.setOut(out);
        }

        assertEquals(2, exit);
        assertTrue(errBuf.toString().contains("invalid --version"), errBuf.toString());
    }

    /**
     * An install reached through a symlink (~/bin/floci -> /opt/floci/floci): the update replaces
     * the file the link points at and leaves the link a link.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void updatingThroughASymlinkReplacesItsTargetAndKeepsTheLink(@TempDir Path dir) throws Exception {
        Path real = Files.writeString(Files.createDirectories(dir.resolve("opt")).resolve("floci"), "old");
        Path link = Files.createSymbolicLink(Files.createDirectories(dir.resolve("bin")).resolve("floci"), real);
        Path fresh = Files.writeString(dir.resolve("download"), "new");

        Path binary = UpdateCommand.realExecutable(link);
        UpdateCommand.replaceAtomically(fresh, binary);

        assertEquals(real.toRealPath(), binary);
        assertTrue(Files.isSymbolicLink(link), "the link is still a link");
        assertEquals(real.toRealPath(), link.toRealPath());
        assertEquals("new", Files.readString(real));
        assertEquals("new", Files.readString(link));
        try (var files = Files.list(real.getParent())) {
            assertEquals(List.of("floci"), files.map(p -> p.getFileName().toString()).toList(), "no staging file left");
        }
    }

    @Test
    void anExecutableThatCannotBeFoundIsReportedWithAWayOut(@TempDir Path dir) {
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> UpdateCommand.realExecutable(dir.resolve("gone")));

        assertTrue(e.getMessage().contains("cannot locate the running executable"), e.getMessage());
        assertTrue(e.getMessage().contains("install.sh"), e.getMessage());
    }

    /** A probe that stalls with its output still open is cut off at the limit, and killed. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aStalledProbeGivesUpAtItsLimit() throws Exception {
        long start = System.nanoTime();

        String out = UpdateCommand.probe(Duration.ofMillis(300), "sh", "-c", "echo 1; exec sleep 30");

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertNull(out);
        assertTrue(elapsedMs < 3_000, "took " + elapsedMs + " ms");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aProbeThatFinishesReturnsItsOutput() throws Exception {
        assertEquals("1", UpdateCommand.probe(Duration.ofSeconds(5), "sh", "-c", "echo 1"));
    }
}
