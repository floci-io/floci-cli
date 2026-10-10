package io.floci.cli.unit;

import io.floci.cli.output.ShellExport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the shell-quoting of every tree's `env` output — it is documented as `eval` input. */
class ShellExportTest {

    @Test
    void bashExportsAreSingleQuoted() {
        assertEquals("export OCI_CLI_ENDPOINT='http://localhost:4599'",
                ShellExport.formatExport("bash", "OCI_CLI_ENDPOINT", "http://localhost:4599"));
    }

    /** Regression for #18: the semicolons must not terminate the export under `eval`. */
    @Test
    void semicolonSeparatedAzureConnectionStringStaysOneAssignment() {
        String conn = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==;"
                + "BlobEndpoint=http://localhost.floci.io:4577/devstoreaccount1;";
        assertEquals("export AZURE_STORAGE_CONNECTION_STRING='" + conn + "'",
                ShellExport.formatExport("bash", "AZURE_STORAGE_CONNECTION_STRING", conn));
    }

    @Test
    void hostileValuesCannotEscapeBashQuoting() {
        String hostile = "http://x\"; echo pwned; $(touch /tmp/pwned) `id` $HOME";
        String export = ShellExport.formatExport("bash", "K", hostile);
        // Entire value stays inside single quotes; no interpolation possible.
        assertEquals("export K='" + hostile + "'", export);

        String withQuote = "it's";
        assertEquals("export K='it'\\''s'", ShellExport.formatExport("bash", "K", withQuote));
    }

    @Test
    void fishEscapesBackslashesAndQuotes() {
        assertEquals("set -x K 'a\\\\b\\'c'",
                ShellExport.formatExport("fish", "K", "a\\b'c"));
    }

    @Test
    void powershellDoublesSingleQuotes() {
        assertEquals("$env:K = 'it''s $x'",
                ShellExport.formatExport("powershell", "K", "it's $x"));
    }

    /** BL-050: the hint says how to load the lines in the shell they were written for. */
    @Test
    void theLoadHintMatchesTheShell() {
        assertEquals("eval \"$(floci env)\"", ShellExport.loadHint("bash", "floci env"));
        assertEquals("eval \"$(floci gcp env)\"", ShellExport.loadHint("zsh", "floci gcp env"));
        assertEquals("floci oci env --shell fish | source", ShellExport.loadHint("fish", "floci oci env"));
        assertEquals("floci az env --shell powershell | Invoke-Expression",
                ShellExport.loadHint("PowerShell", "floci az env"));
    }

    @Test
    void onlyKnownShellsAreSupported() {
        assertTrue(ShellExport.isSupported("bash"));
        assertTrue(ShellExport.isSupported("Fish"));
        assertTrue(ShellExport.isSupported("pwsh"));
        assertFalse(ShellExport.isSupported("tcsh"));
        assertFalse(ShellExport.isSupported(null));
    }
}
