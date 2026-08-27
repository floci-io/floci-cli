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
}
