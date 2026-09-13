package io.floci.cli.unit;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.config.ConfigProfileCommand;
import io.floci.cli.commands.config.ConfigShowCommand;
import io.floci.cli.config.Profile;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 'config show' must merge with the profile rather than replace the command line with it. */
class ConfigCommandsTest {

    @TempDir
    Path tempDir;

    private ProfileStore store() {
        return new ProfileStore(tempDir);
    }

    private void writeProfile(String name, String yaml) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve(name + ".yaml"), yaml);
    }

    private String runShow(ProductProfile product, String... args) {
        CommandLine cmd = new CommandLine(new ConfigShowCommand(product, store()))
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setDefaultValueProvider(new ProfileDefaultValueProvider(store()));
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured));
        try {
            assertEquals(0, cmd.execute(args));
        } finally {
            System.setOut(original);
        }
        return captured.toString();
    }

    @Test
    void showsTheProfilesValues() throws Exception {
        writeProfile("probe", """
                container: floci-probe
                endpoint: http://localhost:4566
                image: floci/floci:enforced
                port: 4599
                persistDir: /tmp/floci-persist-test
                """);

        String out = runShow(ProductProfile.AWS, "--profile", "probe", "-o", "json");
        assertTrue(out.contains("\"container\" : \"floci-probe\""), out);
        assertTrue(out.contains("\"image\" : \"floci/floci:enforced\""), out);
        assertTrue(out.contains("\"persistDir\" : \"/tmp/floci-persist-test\""), out);
    }

    @Test
    void anExplicitFlagIsNoLongerDiscardedByTheProfile() throws Exception {
        writeProfile("probe", """
                container: floci-probe
                endpoint: http://localhost:4566
                """);

        String out = runShow(ProductProfile.AWS, "--profile", "probe",
                "--endpoint", "http://explicit:1234", "-o", "json");

        assertTrue(out.contains("\"endpoint\" : \"http://explicit:1234\""), out);
        assertTrue(out.contains("\"container\" : \"floci-probe\""), out);
    }

    @Test
    void withoutAProfileItReportsTheProductDefaults() {
        String out = runShow(ProductProfile.GCP, "-o", "json");
        assertTrue(out.contains("\"profile\" : \"default\""), out);
        assertTrue(out.contains("\"container\" : \"floci-gcp\""), out);
    }

    @Test
    void createWritesTheDefaultsOfTheTreeItWasRunUnder() throws Exception {
        assertEquals(0, new CommandLine(new ConfigProfileCommand(ProductProfile.GCP, store()))
                .execute("create", "gcp-profile"));

        Profile created = store().get("gcp-profile").orElseThrow();
        assertEquals("floci-gcp", created.container);
        assertEquals("http://localhost:4588", created.endpoint);
        assertEquals("floci/floci-gcp:latest", created.image);
        assertEquals(4588, created.port);
    }
}
