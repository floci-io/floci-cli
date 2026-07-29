package io.floci.cli.unit;

import io.floci.cli.commands.oci.OciSetupCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OciSetupCommandTest {

    @TempDir
    Path tempDir;

    private int run(Path ociDir, String... args) {
        return new CommandLine(new OciSetupCommand(ociDir)).execute(args);
    }

    @Test
    void createsKeyAndConfig() throws Exception {
        Path ociDir = tempDir.resolve(".oci");
        assertEquals(0, run(ociDir));

        Path keyFile = ociDir.resolve("floci_key.pem");
        Path configFile = ociDir.resolve("config");
        assertTrue(Files.exists(keyFile));
        assertTrue(Files.exists(configFile));

        String pem = Files.readString(keyFile);
        assertTrue(pem.startsWith("-----BEGIN PRIVATE KEY-----"));
        assertTrue(pem.contains("OCI_API_KEY"));

        String config = Files.readString(configFile);
        assertTrue(config.contains("[FLOCI]"));
        assertTrue(config.contains("tenancy=ocid1.tenancy.oc1..flocilocaltenancy"));
        assertTrue(config.contains("key_file=" + keyFile.toAbsolutePath()));
        // fingerprint is 16 colon-separated hex bytes
        assertTrue(config.matches("(?s).*fingerprint=([0-9a-f]{2}:){15}[0-9a-f]{2}\n.*"));
    }

    @Test
    void isIdempotent() throws Exception {
        Path ociDir = tempDir.resolve(".oci");
        assertEquals(0, run(ociDir));
        String keyBefore = Files.readString(ociDir.resolve("floci_key.pem"));
        String configBefore = Files.readString(ociDir.resolve("config"));

        assertEquals(0, run(ociDir));
        assertEquals(keyBefore, Files.readString(ociDir.resolve("floci_key.pem")));
        assertEquals(configBefore, Files.readString(ociDir.resolve("config")));
    }

    @Test
    void appendsToExistingConfigWithoutTouchingOtherProfiles() throws Exception {
        Path ociDir = tempDir.resolve(".oci");
        Files.createDirectories(ociDir);
        String existing = "[DEFAULT]\nuser=ocid1.user.oc1..real\nregion=eu-frankfurt-1\n";
        Files.writeString(ociDir.resolve("config"), existing);

        assertEquals(0, run(ociDir));

        String config = Files.readString(ociDir.resolve("config"));
        assertTrue(config.startsWith(existing));
        assertTrue(config.contains("[FLOCI]"));
    }

    @Test
    void honorsCustomProfileName() throws Exception {
        Path ociDir = tempDir.resolve(".oci");
        assertEquals(0, run(ociDir, "--profile-name", "DEFAULT"));
        String config = Files.readString(ociDir.resolve("config"));
        assertTrue(config.contains("[DEFAULT]"));
        assertFalse(config.contains("[FLOCI]"));
    }

    @Test
    void detectsTheProfileSetupWrote() throws Exception {
        Path ociDir = tempDir.resolve(".oci");
        assertNull(OciSetupCommand.detectSetupProfile(ociDir.resolve("config")));

        assertEquals(0, run(ociDir, "--profile-name", "CUSTOM"));
        assertEquals("CUSTOM", OciSetupCommand.detectSetupProfile(ociDir.resolve("config")));

        // A second, FLOCI-named section sharing the setup key wins over CUSTOM
        assertEquals(0, run(ociDir));
        assertEquals("FLOCI", OciSetupCommand.detectSetupProfile(ociDir.resolve("config")));
    }

    @Test
    void detectIgnoresForeignProfiles() throws Exception {
        Path ociDir = tempDir.resolve(".oci");
        Files.createDirectories(ociDir);
        Files.writeString(ociDir.resolve("config"),
                "[PROD]\nuser=ocid1.user.oc1..real\nkey_file=~/.oci/prod_key.pem\n");
        assertNull(OciSetupCommand.detectSetupProfile(ociDir.resolve("config")));
    }
}
