package io.floci.cli.unit;

import io.floci.cli.commands.az.AzEnvCommand;
import io.floci.cli.commands.az.AzSetupCommand;
import io.floci.cli.http.FlociException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AzEnvCommandTest {

    // Containers nobody runs, so the configured endpoint is used and Docker finds nothing.
    private static final String A = "floci-az-envtest-a";
    private static final String B = "floci-az-envtest-b";

    @TempDir
    Path tempDir;

    private AzSetupCommand.CertSource certs = endpoint -> {
        throw new FlociException("Connection refused");
    };

    private String stderr;

    private String run(String container, String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            String[] all = new String[args.length + 2];
            all[0] = "--container";
            all[1] = container;
            System.arraycopy(args, 0, all, 2, args.length);
            assertEquals(0, new CommandLine(new AzEnvCommand(tempDir, certs)).execute(all));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        stderr = errBuf.toString();
        return outBuf.toString();
    }

    private Path dir(String container) {
        return tempDir.resolve("az").resolve(container);
    }

    private void setUpDone(String container) throws Exception {
        Files.createDirectories(dir(container).resolve("azure-config"));
        Files.writeString(dir(container).resolve("ca-bundle.pem"), "bundle");
        Files.writeString(dir(container).resolve("floci-az-ca.pem"), "saved-ca\n");
    }

    @Test
    void beforeSetupOnlyTheStorageVariables() {
        String out = run(A);

        assertTrue(out.contains("AZURE_STORAGE_CONNECTION_STRING"), out);
        assertFalse(out.contains("AZURE_CONFIG_DIR"), out);
        assertFalse(out.contains("REQUESTS_CA_BUNDLE"), out);
    }

    @Test
    void afterSetupPointsAzAtThatInstancesConfig() throws Exception {
        setUpDone(A);

        String out = run(A);

        assertTrue(out.contains("export AZURE_CONFIG_DIR='" + dir(A).resolve("azure-config").toAbsolutePath() + "'"), out);
        assertTrue(out.contains("export REQUESTS_CA_BUNDLE='" + dir(A).resolve("ca-bundle.pem").toAbsolutePath() + "'"), out);
    }

    @Test
    void anotherInstanceDoesNotSeeIt() throws Exception {
        setUpDone(A);

        assertFalse(run(B).contains("AZURE_CONFIG_DIR"));
    }

    @Test
    void afterAGlobalSetupOnlyTheBundle() throws Exception {
        Files.createDirectories(dir(A));
        Files.writeString(dir(A).resolve("ca-bundle.pem"), "bundle");

        String out = run(A);

        // No isolated config to point at, and a stale one from another instance is cleared.
        assertFalse(out.contains("export AZURE_CONFIG_DIR"), out);
        assertTrue(out.contains("unset AZURE_CONFIG_DIR"), out);
        assertTrue(out.contains("REQUESTS_CA_BUNDLE"), out);
        assertTrue(run(A, "--shell", "fish").contains("set -e AZURE_CONFIG_DIR"));
        assertTrue(run(A, "--shell", "powershell").contains("Remove-Item Env:AZURE_CONFIG_DIR"));
    }

    @Test
    void beforeAnySetupAnInheritedConfigDirIsLeftAlone() {
        // Nothing was set up for this instance, so a user's own AZURE_CONFIG_DIR is not ours to clear.
        assertFalse(run(A).contains("AZURE_CONFIG_DIR"));
    }

    @Test
    void sdkVarsModeAndOtherShellsGetThemToo() throws Exception {
        setUpDone(A);

        assertTrue(run(A, "--format", "sdk-vars").contains("export AZURE_CONFIG_DIR="));
        assertTrue(run(A, "--shell", "fish").contains("set -x REQUESTS_CA_BUNDLE '"));
        assertTrue(run(A, "--shell", "powershell").contains("$env:AZURE_CONFIG_DIR = '"));
    }

    @Test
    void jsonCarriesThem() throws Exception {
        setUpDone(A);

        String out = run(A, "-o", "json");

        assertTrue(out.contains("\"AZURE_CONFIG_DIR\""), out);
        assertTrue(out.contains("\"REQUESTS_CA_BUNDLE\""), out);
    }

    @Test
    void warnsOnStderrWhenTheServersCertificateChanged() throws Exception {
        setUpDone(A);
        certs = endpoint -> "a-new-ca\n";

        String out = run(A);

        assertTrue(stderr.contains("certificate changed"), stderr);
        assertTrue(stderr.contains("floci az setup --container " + A), stderr);
        assertFalse(out.contains("certificate changed"), "the warning must not reach eval: " + out);
    }

    @Test
    void noWarningWhenTheCertificateMatches() throws Exception {
        setUpDone(A);
        certs = endpoint -> "saved-ca";

        run(A);

        assertFalse(stderr.contains("certificate changed"), stderr);
    }

    @Test
    void noWarningWhenTheServerIsDown() throws Exception {
        setUpDone(A);

        run(A);

        assertFalse(stderr.contains("Warning"), stderr);
    }
}
