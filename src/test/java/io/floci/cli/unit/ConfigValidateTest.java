package io.floci.cli.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.floci.cli.FlociCli;
import io.floci.cli.ProductProfile;
import io.floci.cli.commands.config.ComposeCheck;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** BL-055: config validate reads the Compose file's structure and checks the emulator's own service. */
class ConfigValidateTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String NEEDED_FOR = "Lambda, EC2";

    @TempDir
    Path tempDir;

    private ComposeCheck.Report aws(String compose) throws Exception {
        return ComposeCheck.analyze(YAML.readTree(compose), ProductProfile.AWS, true, NEEDED_FOR, tempDir);
    }

    private static String service(String ports, String extra) {
        return """
                services:
                  floci:
                    image: floci/floci:latest
                    ports:
                      - %s
                    volumes:
                      - /var/run/docker.sock:/var/run/docker.sock
                %s""".formatted(ports, extra);
    }

    @Test
    void aCompleteServiceHasNothingToReport() throws Exception {
        ComposeCheck.Report r = aws(service("\"4566:4566\"", ""));

        assertEquals("floci", r.service());
        assertEquals(List.of(), r.errors());
        assertEquals(List.of(), r.warnings());
        assertTrue(r.valid());
    }

    /** Any host port works: only the container side has to be the emulator's port. */
    @ParameterizedTest
    @ValueSource(strings = {"\"4566:4566\"", "\"5566:4566\"", "\"127.0.0.1:5566:4566\"", "\"5566:4566/tcp\"",
            "4566", "\"4566\"", "\"14510-14600:4510-4600\"", "{target: 4566, published: 5566}",
            "{target: 4566, published: \"5566\", protocol: tcp}"})
    void everyWayOfMappingTheEmulatorPortIsAccepted(String ports) throws Exception {
        assertEquals(List.of(), aws(service(ports, "")).errors(), ports);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"4566:8080\"", "\"8080:8080\"", "\"45660:45660\"", "8080", "{target: 8080, published: 4566}",
            "\"${HOST_PORT}:${CONTAINER_PORT}\""})
    void aMappingThatDoesNotReachTheEmulatorPortIsAnError(String ports) throws Exception {
        List<String> errors = aws(service(ports, "")).errors();

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("No port mapping for 4566 found"), errors.get(0));
    }

    @Test
    void hostNetworkingNeedsNoPortMapping() throws Exception {
        ComposeCheck.Report r = aws("""
                services:
                  floci:
                    image: floci/floci:latest
                    network_mode: host
                    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
                """);

        assertEquals(List.of(), r.errors());
    }

    /** Another service's port or socket mount must not satisfy the emulator's checks. */
    @Test
    void onlyTheEmulatorsOwnServiceCounts() throws Exception {
        ComposeCheck.Report r = aws("""
                services:
                  proxy:
                    image: nginx
                    ports: ["4566:4566"]
                    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
                  floci:
                    image: ghcr.io/floci-io/floci:1.6.0
                """);

        assertEquals("floci", r.service());
        assertEquals(2, r.errors().size(), r.errors().toString());
        assertTrue(r.errors().get(0).contains("docker.sock"), r.errors().toString());
        assertTrue(r.errors().get(1).contains("No port mapping for 4566"), r.errors().toString());
    }

    @Test
    void theSocketIsOnlyAWarningWhereTheProductDoesNotNeedIt() throws Exception {
        JsonNode compose = YAML.readTree("""
                services:
                  gcp:
                    image: floci/floci-gcp:latest
                    ports: ["4588:4588"]
                """);

        ComposeCheck.Report r = ComposeCheck.analyze(compose, ProductProfile.GCP, false, "Cloud Run", tempDir);

        assertTrue(r.valid());
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("docker.sock"), r.warnings().toString());
    }

    @Test
    void theLongVolumeFormIsRead() throws Exception {
        ComposeCheck.Report r = aws("""
                services:
                  floci:
                    image: floci/floci
                    ports: ["4566:4566"]
                    volumes:
                      - type: bind
                        source: /var/run/docker.sock
                        target: /var/run/docker.sock
                """);

        assertEquals(List.of(), r.errors());
    }

    // ---- storage mode and the data volume ----------------------------------------------------

    @Test
    void aDataVolumeWithoutPersistentStorageIsFlagged() throws Exception {
        ComposeCheck.Report r = aws(service("\"4566:4566\"", "      - ./data:/app/data\n"));

        assertTrue(r.valid());
        assertEquals(1, r.warnings().size(), r.warnings().toString());
        assertTrue(r.warnings().get(0).contains("FLOCI_STORAGE_MODE is not 'persistent'"), r.warnings().get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"""
                environment:
                  FLOCI_STORAGE_MODE: persistent
            """, """
                environment:
                  - FLOCI_STORAGE_MODE=persistent
            """, """
                environment:
                  FLOCI_STORAGE_MODE: ${MODE:-memory}
            """})
    void aDataVolumeWithPersistentStorageIsFine(String environment) throws Exception {
        ComposeCheck.Report r = aws(service("\"4566:4566\"", "      - floci-data:/app/data\n" + environment));

        assertEquals(List.of(), r.warnings(), environment);
    }

    @Test
    void persistentStorageWithoutADataVolumeIsFlagged() throws Exception {
        ComposeCheck.Report r = aws(service("\"4566:4566\"", "    environment:\n      FLOCI_STORAGE_MODE: persistent\n"));

        assertEquals(1, r.warnings().size(), r.warnings().toString());
        assertTrue(r.warnings().get(0).contains("nothing is mounted at /app/data"), r.warnings().get(0));
    }

    @Test
    void theStorageVariableIsTheProductsOwn() throws Exception {
        JsonNode compose = YAML.readTree("""
                services:
                  oci:
                    image: floci/floci-oci:latest
                    ports: ["4599:4599"]
                    volumes: ["./state:/app/data", "/var/run/docker.sock:/var/run/docker.sock"]
                    environment:
                      FLOCI_STORAGE_MODE: persistent
                """);

        ComposeCheck.Report r = ComposeCheck.analyze(compose, ProductProfile.OCI, true, "Functions", tempDir);

        assertTrue(r.warnings().get(0).contains("FLOCI_OCI_STORAGE_MODE is not 'persistent'"), r.warnings().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"      - ./data:/app/data:ro\n", "      - floci-data:/app/data/:ro,z\n",
            "      - {type: bind, source: ./data, target: /app/data, read_only: true}\n"})
    void aReadOnlyDataVolumeIsAnError(String volume) throws Exception {
        ComposeCheck.Report r = aws(service("\"4566:4566\"", volume + "    environment: {FLOCI_STORAGE_MODE: persistent}\n"));

        assertEquals(1, r.errors().size(), r.errors().toString());
        assertTrue(r.errors().get(0).contains("mounted read-only"), r.errors().get(0));
    }

    @Test
    void aDataVolumeBoundToAFileIsAnError() throws Exception {
        Files.writeString(tempDir.resolve("data"), "not a directory");

        ComposeCheck.Report r = aws(service("\"4566:4566\"",
                "      - ./data:/app/data\n    environment: {FLOCI_STORAGE_MODE: persistent}\n"));

        assertEquals(1, r.errors().size(), r.errors().toString());
        assertTrue(r.errors().get(0).contains("which is a file, not a directory"), r.errors().get(0));
    }

    /** A directory, a path that does not exist yet and a named volume say nothing against the mount. */
    @ParameterizedTest
    @ValueSource(strings = {"./state", "./not-there-yet", "floci-data", "${DATA_DIR}"})
    void otherDataSourcesAreNotJudged(String source) throws Exception {
        Files.createDirectories(tempDir.resolve("state"));

        ComposeCheck.Report r = aws(service("\"4566:4566\"",
                "      - " + source + ":/app/data\n    environment: {FLOCI_STORAGE_MODE: persistent}\n"));

        assertEquals(List.of(), r.errors(), source);
    }

    // ---- image ---------------------------------------------------------------------------------

    @Test
    void anImageOlderThanTheSupportedMinimumIsFlagged() throws Exception {
        ComposeCheck.Report r = aws(service("\"4566:4566\"", "").replace("floci/floci:latest", "floci/floci:1.4.2"));

        assertTrue(r.valid());
        assertEquals(1, r.warnings().size(), r.warnings().toString());
        assertTrue(r.warnings().get(0).contains("Image tag 1.4.2 is older than 1.5.0"), r.warnings().get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"floci/floci", "floci/floci:latest", "floci/floci:1.5.0", "floci/floci:2.0.1",
            "ghcr.io/floci-io/floci:1.6.0", "docker.io/floci/floci:1.6.0", "floci/floci:1.6.0@sha256:abc", "floci/floci:nightly"})
    void supportedOrUnversionedImagesAreNotFlagged(String image) throws Exception {
        ComposeCheck.Report r = aws(service("\"4566:4566\"", "").replace("floci/floci:latest", image));

        assertEquals("floci", r.service(), image);
        assertEquals(List.of(), r.warnings(), image);
    }

    @Test
    void repositoriesAndTagsAreSplitAtTheRightColon() {
        assertEquals("registry.local:5000/floci/floci", ComposeCheck.repositoryOf("registry.local:5000/floci/floci:1.6.0"));
        assertEquals("1.6.0", ComposeCheck.tagOf("registry.local:5000/floci/floci:1.6.0"));
        assertEquals("registry.local:5000/floci/floci", ComposeCheck.repositoryOf("registry.local:5000/floci/floci"));
        assertEquals("", ComposeCheck.tagOf("registry.local:5000/floci/floci"));
    }

    /** One service and no recognisable image (built locally, mirrored): it is still the one to check. */
    @Test
    void aSingleServiceIsCheckedEvenWithoutARecognisedImage() throws Exception {
        ComposeCheck.Report r = aws("""
                services:
                  emulator:
                    build: .
                """);

        assertEquals("emulator", r.service());
        assertEquals(2, r.errors().size(), r.errors().toString());
        assertTrue(r.warnings().get(0).contains("image reference not detected"), r.warnings().toString());
    }

    /** Several services and none recognisable: checking one at random would only mislead. */
    @Test
    void severalServicesWithoutARecognisedImageAreNotGuessedAt() throws Exception {
        ComposeCheck.Report r = aws("""
                services:
                  web: {image: nginx}
                  db: {image: postgres}
                """);

        assertNull(r.service());
        assertEquals(List.of(), r.errors());
        assertEquals(1, r.warnings().size());
    }

    @Test
    void aFileWithoutServicesIsAnError() throws Exception {
        ComposeCheck.Report r = aws("version: '3'\n");

        assertFalse(r.valid());
        assertTrue(r.errors().get(0).startsWith("No services found"), r.errors().toString());
    }

    // ---- the command ---------------------------------------------------------------------------

    private record Run(int exit, String out, String err) {}

    private Run run(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            int exit = FlociCli.buildCommandLine(new ProfileStore(tempDir.resolve("profiles"))).execute(args);
            return new Run(exit, outBuf.toString(), errBuf.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    @Test
    void jsonOutputCarriesTheFindingsAndTheExitCode() throws Exception {
        Path file = Files.writeString(tempDir.resolve("compose.yml"), """
                services:
                  floci:
                    image: floci/floci:1.4.0
                    ports: ["4566:8080"]
                    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
                """);

        Run r = run("config", "validate", "-f", file.toString(), "-o", "json");

        assertEquals(1, r.exit());
        JsonNode json = new ObjectMapper().readTree(r.out());
        assertEquals(file.toString(), json.path("file").asText());
        assertEquals("floci", json.path("service").asText());
        assertFalse(json.path("valid").asBoolean());
        assertEquals(1, json.path("errors").size());
        assertEquals(1, json.path("warnings").size());
        assertFalse(r.out().contains("Validating"), "no text banner in structured output");
    }

    @Test
    void aValidFileExitsZeroInEveryTree() throws Exception {
        Path file = Files.writeString(tempDir.resolve("compose.yml"), """
                services:
                  gcp:
                    image: floci/floci-gcp:latest
                    ports: ["14588:4588"]
                    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
                """);

        Run text = run("gcp", "config", "validate", "-f", file.toString());
        Run yaml = run("gcp", "config", "validate", "-f", file.toString(), "-o", "yaml");

        assertEquals(0, text.exit(), text.out() + text.err());
        assertTrue(text.out().contains("Compose file looks good"), text.out());
        assertEquals(0, yaml.exit());
        assertTrue(yaml.out().contains("valid: true"), yaml.out());
    }

    @Test
    void aFileThatIsNotYamlIsReportedWithANextStep() throws Exception {
        Path file = Files.writeString(tempDir.resolve("compose.yml"), "services: [unterminated\n");

        Run r = run("config", "validate", "-f", file.toString());

        assertEquals(1, r.exit());
        assertTrue(r.err().contains("as YAML"), r.err());
        assertTrue(r.err().contains("pass another with --file"), r.err());
    }
}
