package io.floci.cli.unit;

import io.floci.cli.commands.UpdateCommand;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

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
}
