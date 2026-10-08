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
    void reportsTheInterpolatedPersistDirThatStartWouldActuallyUse() throws Exception {
        writeProfile("interp", "persistDir: ${env:HOME}/floci-data\n");

        String out = runShow(ProductProfile.AWS, "--profile", "interp", "-o", "json");

        assertTrue(out.contains("\"persistDir\" : \"" + System.getenv("HOME") + "/floci-data\""), out);
        assertFalse(out.contains("${env:"), out);
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

    private record Run(int exit, String out, String err) {}

    private Run profileCmd(ProductProfile product, String... args) {
        CommandLine cmd = new CommandLine(new ConfigProfileCommand(product, store()))
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setDefaultValueProvider(new ProfileDefaultValueProvider(store()));
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            int exit = cmd.execute(args);
            return new Run(exit, outBuf.toString(), errBuf.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    @Test
    void createTakesTheInstanceFlagsInEveryTree() throws Exception {
        for (ProductProfile product : new ProductProfile[]{ProductProfile.AWS, ProductProfile.GCP,
                ProductProfile.AZ, ProductProfile.OCI}) {
            String name = "inst-" + product.name();
            assertEquals(0, profileCmd(product, "create", name,
                    "--container", "floci-" + product.name() + "-b", "--port", "14000",
                    "--persist", "/data/" + product.name(), "--services", "x,y").exit());

            Profile p = store().get(name).orElseThrow();
            assertEquals("floci-" + product.name() + "-b", p.container);
            assertEquals(14000, p.port);
            assertEquals("/data/" + product.name(), p.persistDir);
            assertEquals("x,y", p.services);
            // Untyped flags keep the defaults of the tree create ran under.
            assertEquals(product.defaultEndpoint(), p.endpoint);
            assertEquals(product.defaultImageRef(), p.image);
        }
    }

    @Test
    void createIgnoresAnotherProfilesValuesAndTakesOnlyTypedFlags() throws Exception {
        writeProfile("a", "container: floci-az-a\nport: 14577\npersistDir: /data/a\n");

        assertEquals(0, profileCmd(ProductProfile.AZ, "create", "b", "--profile", "a", "--port", "14578").exit());

        Profile b = store().get("b").orElseThrow();
        assertEquals(14578, b.port);
        assertEquals("floci-az", b.container);
        assertNull(b.persistDir);
        assertNull(b.namespace);
    }

    @Test
    void createStoresATypedNamespace() throws Exception {
        assertEquals(0, profileCmd(ProductProfile.GCP, "create", "ns",
                "--container", "floci-gcp-b", "--namespace", "team-b").exit());

        assertEquals("team-b", store().get("ns").orElseThrow().namespace);
    }

    @Test
    void showReportsTheNamespaceAProfilesContainerImplies() throws Exception {
        writeProfile("b", "container: floci-b\n");
        writeProfile("plain", "port: 4599\n");

        assertTrue(runShow(ProductProfile.AWS, "--profile", "b", "-o", "json")
                .contains("\"namespace\" : \"b\""));
        assertFalse(runShow(ProductProfile.AWS, "--profile", "plain", "-o", "json")
                .contains("namespace"));
    }

    @Test
    void createWarnsWhenTwoInstancesOfOneEmulatorWouldShareANamespace() throws Exception {
        writeProfile("a", "container: floci-gcp-b\nport: 14588\nimage: floci/floci-gcp:latest\n");

        Run same = profileCmd(ProductProfile.GCP, "create", "c", "--container", "b", "--port", "14589");
        assertTrue(same.err().contains("resource namespace 'b'"), same.err());

        Run own = profileCmd(ProductProfile.GCP, "create", "d", "--container", "b", "--port", "14590",
                "--namespace", "team-d");
        assertFalse(own.err().contains("resource namespace"), own.err());
    }

    @Test
    void createRefusesAPortOutOfRange() throws Exception {
        Run r = profileCmd(ProductProfile.AZ, "create", "bad", "--port", "70000");

        assertEquals(1, r.exit());
        assertTrue(store().get("bad").isEmpty());
    }

    @Test
    void createWarnsWhenAnotherProfileClaimsTheSameContainerOrPort() throws Exception {
        writeProfile("a", "container: floci-az-a\nport: 14577\nimage: floci/floci-az:latest\n");

        Run sameContainer = profileCmd(ProductProfile.AZ, "create", "b", "--container", "floci-az-a", "--port", "14999");
        assertEquals(0, sameContainer.exit());
        assertTrue(sameContainer.err().contains("Profile 'a' already uses container 'floci-az-a'"), sameContainer.err());

        Run samePort = profileCmd(ProductProfile.AZ, "create", "c", "--container", "floci-az-c", "--port", "14577");
        assertTrue(samePort.err().contains("Profile 'a' already uses port 14577"), samePort.err());

        // Another emulator on the same port is not this instance's concern.
        Run otherProduct = profileCmd(ProductProfile.GCP, "create", "d", "--container", "floci-gcp-d", "--port", "14577");
        assertFalse(otherProduct.err().contains("already uses"), otherProduct.err());
    }

    @Test
    void aDigestPinnedImageStillCollidesWithATagOfTheSameRepository() throws Exception {
        writeProfile("a", "container: floci-az-a\nport: 14577\nimage: floci/floci-az:latest\n");

        Run pinned = profileCmd(ProductProfile.AZ, "create", "b", "--container", "floci-az-b", "--port", "14577",
                "--image", "floci/floci-az@sha256:0123456789abcdef");

        assertTrue(pinned.err().contains("Profile 'a' already uses port 14577"), pinned.err());
    }

    @Test
    void listAsJsonIsAnEmptyArrayWhenThereAreNoProfiles() {
        Run r = profileCmd(ProductProfile.AWS, "list", "-o", "json");

        assertEquals(0, r.exit());
        assertEquals("[ ]", r.out().strip());
    }

    @Test
    void listShowsEachInstancesContainerPortAndDataDir() throws Exception {
        writeProfile("b", "container: floci-az-b\nport: 14578\n");
        writeProfile("a", "container: floci-az-a\nport: 14577\npersistDir: /data/a\n");

        String text = profileCmd(ProductProfile.AZ, "list", "--no-color").out();
        assertTrue(text.indexOf("floci-az-a") < text.indexOf("floci-az-b"), "sorted by name: " + text);
        assertTrue(text.contains("floci-az-a") && text.contains(":14577") && text.contains("/data/a"), text);
        assertTrue(text.contains("floci-az-b") && text.contains(":14578"), text);

        String json = profileCmd(ProductProfile.AZ, "list", "-o", "json").out();
        assertTrue(json.trim().startsWith("["), json);
        assertTrue(json.contains("\"container\" : \"floci-az-b\""), json);
        assertTrue(json.contains("\"port\" : 14577"), json);
    }
}
