package io.floci.cli.unit;

import io.floci.cli.commands.oci.OciEnvCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the shell-quoting of `floci oci env` output — it is documented as `eval` input. */
class OciEnvFormatTest {

    @Test
    void bashExportsAreSingleQuoted() {
        assertEquals("export OCI_CLI_ENDPOINT='http://localhost:4599'",
                OciEnvCommand.formatExport("bash", "OCI_CLI_ENDPOINT", "http://localhost:4599"));
    }

    @Test
    void hostileValuesCannotEscapeBashQuoting() {
        String hostile = "http://x\"; echo pwned; $(touch /tmp/pwned) `id` $HOME";
        String export = OciEnvCommand.formatExport("bash", "K", hostile);
        // Entire value stays inside single quotes; no interpolation possible.
        assertEquals("export K='" + hostile + "'", export);

        String withQuote = "it's";
        assertEquals("export K='it'\\''s'", OciEnvCommand.formatExport("bash", "K", withQuote));
    }

    @Test
    void fishEscapesBackslashesAndQuotes() {
        assertEquals("set -x K 'a\\\\b\\'c'",
                OciEnvCommand.formatExport("fish", "K", "a\\b'c"));
    }

    @Test
    void powershellDoublesSingleQuotes() {
        assertEquals("$env:K = 'it''s $x'",
                OciEnvCommand.formatExport("powershell", "K", "it's $x"));
    }
}
