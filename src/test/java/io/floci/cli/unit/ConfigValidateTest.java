package io.floci.cli.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.floci.cli.FlociCli;
import io.floci.cli.ProductProfile;
import io.floci.cli.commands.config.ComposeCheck;
import io.floci.cli.commands.config.YamlTree;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
        return ComposeCheck.analyze(YamlTree.read(compose), ProductProfile.AWS, true, NEEDED_FOR, tempDir);
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

    /** The emulator's endpoint is HTTP: a UDP mapping of the same number does not reach it. */
    @ParameterizedTest
    @ValueSource(strings = {"\"4566:4566/udp\"", "{target: 4566, published: 5566, protocol: udp}", "\"5566:4566/sctp\""})
    void aMappingThatIsNotTcpIsAnError(String ports) throws Exception {
        List<String> errors = aws(service(ports, "")).errors();

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("No port mapping for 4566 found"), errors.get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"4566:4566/TCP\"", "{target: 4566, published: 5566, protocol: tcp}", "{target: 4566}"})
    void tcpIsAcceptedHoweverItIsSpelled(String ports) throws Exception {
        assertEquals(List.of(), aws(service(ports, "")).errors(), ports);
    }

    /** What matters is where the socket lands in the container; the host side may be any socket. */
    @ParameterizedTest
    @ValueSource(strings = {"/run/user/1000/podman/podman.sock:/var/run/docker.sock", "/var/run/docker.sock:/var/run/docker.sock:ro",
            "{type: bind, source: /var/run/docker.sock, target: /var/run/docker.sock}"})
    void aSocketMountedAtTheExpectedPathIsAccepted(String volume) throws Exception {
        assertEquals(List.of(), aws(service("\"4566:4566\"", "").replace("/var/run/docker.sock:/var/run/docker.sock", volume)).errors());
    }

    @ParameterizedTest
    @ValueSource(strings = {"./notdocker.sock:/tmp/notdocker.sock", "/var/run/docker.sock:/tmp/docker.sock",
            "{type: bind, source: /var/run/docker.sock, target: /run/docker.sock}"})
    void aSocketMountedSomewhereElseDoesNotCount(String volume) throws Exception {
        List<String> errors = aws(service("\"4566:4566\"", "").replace("/var/run/docker.sock:/var/run/docker.sock", volume)).errors();

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("Missing /var/run/docker.sock volume mount"), errors.get(0));
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
        JsonNode compose = YamlTree.read("""
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

    /** An env_file may set the storage mode; it is not read, so nothing is claimed about it. */
    @ParameterizedTest
    @ValueSource(strings = {"    env_file: .env\n", "    env_file:\n      - .env\n      - .env.local\n"})
    void anEnvFileMakesTheStorageModeUnknown(String envFile) throws Exception {
        ComposeCheck.Report r = aws(service("\"4566:4566\"", "      - ./state:/app/data\n" + envFile));

        assertEquals(List.of(), r.warnings(), envFile);
    }

    /** A mode the file itself spells out is still judged, env_file or not. */
    @Test
    void anExplicitStorageModeIsJudgedEvenWithAnEnvFile() throws Exception {
        ComposeCheck.Report r = aws(service("\"4566:4566\"",
                "      - ./state:/app/data\n    env_file: .env\n    environment: {FLOCI_STORAGE_MODE: memory}\n"));

        assertEquals(1, r.warnings().size(), r.warnings().toString());
    }

    @Test
    void persistentStorageWithoutADataVolumeIsFlagged() throws Exception {
        ComposeCheck.Report r = aws(service("\"4566:4566\"", "    environment:\n      FLOCI_STORAGE_MODE: persistent\n"));

        assertEquals(1, r.warnings().size(), r.warnings().toString());
        assertTrue(r.warnings().get(0).contains("nothing is mounted at /app/data"), r.warnings().get(0));
    }

    @Test
    void theStorageVariableIsTheProductsOwn() throws Exception {
        JsonNode compose = YamlTree.read("""
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

    /** In the long form the type says the source is a host path, even when it is a bare name. */
    @Test
    void aLongFormBindToAFileIsAnErrorEvenWithABareName() throws Exception {
        Files.writeString(tempDir.resolve("data"), "not a directory");

        ComposeCheck.Report bind = aws(service("\"4566:4566\"",
                "      - {type: bind, source: data, target: /app/data}\n    environment: {FLOCI_STORAGE_MODE: persistent}\n"));
        ComposeCheck.Report named = aws(service("\"4566:4566\"",
                "      - {type: volume, source: data, target: /app/data}\n    environment: {FLOCI_STORAGE_MODE: persistent}\n"));
        ComposeCheck.Report shortNamed = aws(service("\"4566:4566\"",
                "      - data:/app/data\n    environment: {FLOCI_STORAGE_MODE: persistent}\n"));

        assertEquals(1, bind.errors().size(), bind.errors().toString());
        assertTrue(bind.errors().get(0).contains("which is a file, not a directory"), bind.errors().get(0));
        assertEquals(List.of(), named.errors(), "a named volume called 'data' is not the file");
        assertEquals(List.of(), shortNamed.errors(), "nor is the short form's bare name");
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

    // ---- anchors, aliases and merge keys ---------------------------------------------------------

    /** BL-085: a service built from an anchor with a merge key is read the way Compose reads it. */
    @Test
    void aServiceBuiltFromAnAnchorIsReadInFull() throws Exception {
        ComposeCheck.Report r = aws("""
                x-floci: &floci
                  image: floci/floci:latest
                  ports:
                    - "4566:4566"
                  volumes:
                    - /var/run/docker.sock:/var/run/docker.sock
                services:
                  floci:
                    <<: *floci
                """);

        assertEquals("floci", r.service());
        assertEquals(List.of(), r.errors());
        assertEquals(List.of(), r.warnings());
    }

    @Test
    void anAliasedListIsReadAsTheList() throws Exception {
        ComposeCheck.Report r = aws("""
                x-volumes: &v
                  - /var/run/docker.sock:/var/run/docker.sock
                x-ports: &p ["5566:4566"]
                services:
                  floci:
                    image: floci/floci:latest
                    ports: *p
                    volumes: *v
                """);

        assertEquals(List.of(), r.errors());
    }

    /** A key written in the service wins over the one a merge brings in, wherever '<<' stands. */
    @Test
    void anExplicitKeyOverridesTheMergedOne() throws Exception {
        ComposeCheck.Report r = aws("""
                x-base: &base
                  image: nginx
                  ports: ["8080:8080"]
                  volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
                services:
                  floci:
                    image: floci/floci:latest
                    ports: ["4566:4566"]
                    <<: *base
                """);

        assertEquals("floci", r.service());
        assertEquals(List.of(), r.errors());
    }

    @Test
    void aMergeOfSeveralMappingsTakesTheEarlierOneFirst() throws Exception {
        JsonNode tree = YamlTree.read("""
                a: &a {x: 1, y: 1}
                b: &b {y: 2, z: 2}
                c:
                  <<: [*a, *b]
                  z: 3
                """);

        assertEquals(1, tree.path("c").path("x").asInt());
        assertEquals(1, tree.path("c").path("y").asInt());
        assertEquals(3, tree.path("c").path("z").asInt());
        assertFalse(tree.path("c").has("<<"));
    }

    /** An alias is a copy: what is checked for one service does not leak into the anchor's owner. */
    @Test
    void anAnchoredServiceCanBeReusedByAnother() throws Exception {
        ComposeCheck.Report r = aws("""
                services:
                  floci: &main
                    image: floci/floci:latest
                    ports: ["4566:4566"]
                    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
                  floci-b:
                    <<: *main
                    ports: ["14566:4566"]
                """);

        assertEquals("floci", r.service());
        assertEquals(List.of(), r.errors());
    }

    @Test
    void scalarsKeepTheirTypesAndPlainDocumentsReadAsBefore() throws Exception {
        JsonNode tree = YamlTree.read("""
                name: floci
                port: 4566
                ratio: 1.5
                enabled: true
                nothing: null
                tag: "1.6.0"
                list: [1, "two", {three: 3}]
                anchored: &s shared
                again: *s
                """);

        assertEquals("floci", tree.path("name").asText());
        assertTrue(tree.path("port").isIntegralNumber());
        assertEquals(4566, tree.path("port").asInt());
        assertEquals(1.5, tree.path("ratio").asDouble());
        assertTrue(tree.path("enabled").isBoolean());
        assertTrue(tree.path("nothing").isNull());
        assertTrue(tree.path("tag").isTextual());
        assertEquals(3, tree.path("list").get(2).path("three").asInt());
        assertEquals("shared", tree.path("again").asText());
    }

    /**
     * Sixteen levels, three aliases each: 48 aliases, under the parser's own limit, and 43 million
     * values once expanded. The reader stops at its budget instead of building that tree.
     */
    @Test
    void aFileThatExpandsToMillionsOfValuesIsRefusedQuickly() {
        StringBuilder bomb = new StringBuilder("x-0: &l0 [a, b, c]\n");
        for (int level = 1; level <= 16; level++) {
            bomb.append("x-").append(level).append(": &l").append(level)
                    .append(" [*l").append(level - 1).append(", *l").append(level - 1).append(", *l").append(level - 1).append("]\n");
        }
        bomb.append("services:\n  floci:\n    image: floci/floci:latest\n");

        long start = System.nanoTime();
        IOException e = assertThrows(IOException.class, () -> YamlTree.read(bomb.toString()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(e.getMessage().contains("once its aliases are expanded"), e.getMessage());
        assertTrue(elapsedMs < 5_000, "took " + elapsedMs + " ms");
    }

    @Test
    void theCommandReportsSuchAFileInsteadOfRunningOutOfMemory() throws Exception {
        StringBuilder bomb = new StringBuilder("x-0: &l0 [a, b, c]\n");
        for (int level = 1; level <= 16; level++) {
            bomb.append("x-").append(level).append(": &l").append(level)
                    .append(" [*l").append(level - 1).append(", *l").append(level - 1).append(", *l").append(level - 1).append("]\n");
        }
        Path file = Files.writeString(tempDir.resolve("compose.yml"), bomb + "services: {}\n");

        Run r = run("config", "validate", "-f", file.toString(), "-o", "json");

        assertEquals(1, r.exit());
        JsonNode json = new ObjectMapper().readTree(r.out());
        assertFalse(json.path("valid").asBoolean());
        assertTrue(json.path("errors").get(0).asText().contains("once its aliases are expanded"), r.out());
    }

    /**
     * An alias that refers to itself is a collection containing itself. It is reported like any
     * other unreadable file, not left to overflow the stack.
     */
    @ParameterizedTest
    @ValueSource(strings = {"x: &a [*a]\n", "x: &a {self: *a}\n", "x: &a [[[*a]]]\nservices: {}\n"})
    void aRecursiveAliasIsReportedNotFollowed(String yaml) {
        IOException e = assertThrows(IOException.class, () -> YamlTree.read(yaml));

        assertTrue(e.getMessage().contains("an alias refers to itself"), e.getMessage());
    }

    @Test
    void theCommandReportsARecursiveAlias() throws Exception {
        Path file = Files.writeString(tempDir.resolve("compose.yml"), "x-loop: &a [*a]\nservices: {}\n");

        Run json = run("config", "validate", "-f", file.toString(), "-o", "json");
        Run text = run("config", "validate", "-f", file.toString());

        assertEquals(1, json.exit());
        assertFalse(new ObjectMapper().readTree(json.out()).path("valid").asBoolean());
        assertTrue(json.out().contains("an alias refers to itself"), json.out());
        assertEquals(1, text.exit());
        assertTrue(text.err().contains("an alias refers to itself"), text.err());
        assertFalse(text.err().contains("StackOverflow"), text.err());
    }

    /** Ordinary nesting, well past what a Compose file uses, is still read. */
    @Test
    void deepButFiniteNestingIsRead() throws Exception {
        StringBuilder yaml = new StringBuilder("a: ");
        yaml.append("[".repeat(40)).append("x").append("]".repeat(40)).append("\n");

        assertTrue(YamlTree.read(yaml.toString()).path("a").isArray());
    }

    /** A real Compose file with many aliases stays far below the budget. */
    @Test
    void manyOrdinaryAliasesAreFine() throws Exception {
        StringBuilder compose = new StringBuilder("x-env: &env {A: 1, B: 2, C: 3}\nservices:\n");
        for (int i = 0; i < 40; i++) {
            compose.append("  svc").append(i).append(":\n    image: busybox\n    environment: *env\n");
        }
        compose.append("  floci:\n    image: floci/floci:latest\n    ports: [\"4566:4566\"]\n"
                + "    volumes: [\"/var/run/docker.sock:/var/run/docker.sock\"]\n");

        ComposeCheck.Report r = aws(compose.toString());

        assertEquals("floci", r.service());
        assertEquals(List.of(), r.errors());
    }

    /**
     * Compose reads YAML 1.2, where only true and false are booleans. A service or key called
     * on, off, yes or no keeps its spelling instead of turning into true or false.
     */
    @ParameterizedTest
    @ValueSource(strings = {"on", "off", "yes", "no", "y", "n", "On", "NO"})
    void aServiceNamedLikeAYaml11BooleanKeepsItsName(String name) throws Exception {
        ComposeCheck.Report r = aws("""
                services:
                  %s:
                    image: floci/floci:latest
                    ports: ["4566:4566"]
                    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
                """.formatted(name));

        assertEquals(name, r.service());
        assertEquals(List.of(), r.errors());
    }

    @Test
    void onlyTrueAndFalseAreBooleans() throws Exception {
        JsonNode tree = YamlTree.read("""
                a: true
                b: False
                c: yes
                d: off
                e: 2024-01-01
                f: 0x1F
                g: ~
                """);

        assertTrue(tree.path("a").isBoolean() && tree.path("a").asBoolean());
        assertTrue(tree.path("b").isBoolean() && !tree.path("b").asBoolean());
        assertEquals("yes", tree.path("c").asText());
        assertTrue(tree.path("c").isTextual());
        assertEquals("off", tree.path("d").asText());
        assertEquals("2024-01-01", tree.path("e").asText());
        assertTrue(tree.path("f").isIntegralNumber());
        assertTrue(tree.path("g").isNull());
    }

    /** read_only written as true still makes a data volume read-only. */
    @Test
    void aBooleanValueIsStillABoolean() throws Exception {
        ComposeCheck.Report r = aws(service("\"4566:4566\"",
                "      - {type: bind, source: ./data, target: /app/data, read_only: true}\n    environment: {FLOCI_STORAGE_MODE: persistent}\n"));

        assertEquals(1, r.errors().size(), r.errors().toString());
        assertTrue(r.errors().get(0).contains("mounted read-only"), r.errors().get(0));
    }

    @Test
    void anAliasWithoutItsAnchorIsAParseError() {
        assertThrows(IOException.class, () -> YamlTree.read("services:\n  floci: *missing\n"));
    }

    @Test
    void anEmptyDocumentIsMissing() throws Exception {
        assertTrue(YamlTree.read("").isMissingNode());
        assertTrue(YamlTree.read("# only a comment\n").isMissingNode());
    }

    /** An anchor on a plain value, such as a shared image tag, resolves like any other. */
    @Test
    void anAnchorOnAScalarResolves() throws Exception {
        ComposeCheck.Report r = aws("""
                x-image: &image floci/floci:1.6.0
                x-port: &port "4566:4566"
                services:
                  floci:
                    image: *image
                    ports: [*port]
                    volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
                """);

        assertEquals("floci", r.service());
        assertEquals(List.of(), r.errors());
        assertEquals(List.of(), r.warnings());
    }

    /** Through the command: the file from the report exits 0. */
    @Test
    void theCommandAcceptsAComposeFileWithAnchors() throws Exception {
        Path file = Files.writeString(tempDir.resolve("compose.yml"), """
                x-floci: &floci
                  image: floci/floci:latest
                  volumes: &sock
                    - /var/run/docker.sock:/var/run/docker.sock
                services:
                  floci:
                    <<: *floci
                    ports: ["4566:4566"]
                  worker:
                    image: busybox
                    volumes: *sock
                """);

        Run r = run("config", "validate", "-f", file.toString(), "-o", "json");

        assertEquals(0, r.exit(), r.out() + r.err());
        JsonNode json = new ObjectMapper().readTree(r.out());
        assertTrue(json.path("valid").asBoolean(), r.out());
        assertEquals("floci", json.path("service").asText());
        assertEquals(0, json.path("errors").size());
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

    /** A script reading the report gets one for a file that cannot be parsed too. */
    @ParameterizedTest
    @ValueSource(strings = {"services: [unterminated\n", "- just\n- a list\n", "plain text\n"})
    void aFileThatCannotBeReadStillGetsAStructuredReport(String content) throws Exception {
        Path file = Files.writeString(tempDir.resolve("compose.yml"), content);

        Run r = run("config", "validate", "-f", file.toString(), "-o", "json");

        assertEquals(1, r.exit());
        JsonNode json = new ObjectMapper().readTree(r.out());
        assertEquals(file.toString(), json.path("file").asText());
        assertTrue(json.path("service").isNull(), r.out());
        assertFalse(json.path("valid").asBoolean());
        assertEquals(1, json.path("errors").size());
        assertTrue(json.path("errors").get(0).asText().startsWith("Could not read "), r.out());
        assertEquals(0, json.path("warnings").size());
        assertEquals("", r.err());
    }
}
