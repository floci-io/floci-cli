package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** BL-050: every env command refuses a shell it cannot write for, instead of printing bash. */
class EnvShellOptionTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"aws", "gcp", "az", "oci"})
    void anUnknownShellExitsTwo(String product) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        int exit;
        try {
            exit = FlociCli.buildCommandLine(new ProfileStore(tempDir)).execute(product, "env",
                    "--shell", "tcsh", "--endpoint", "http://localhost:1", "--container", "floci-env-test-none");
        } finally {
            System.setOut(out);
            System.setErr(err);
        }

        assertEquals(2, exit);
        assertTrue(errBuf.toString().contains("Unknown shell 'tcsh'"), errBuf.toString());
        assertEquals("", outBuf.toString());
    }
}
