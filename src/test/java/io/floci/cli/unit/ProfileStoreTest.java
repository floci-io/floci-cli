package io.floci.cli.unit;

import io.floci.cli.ProductProfile;
import io.floci.cli.config.Profile;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** First coverage of the profile persistence layer, including the path-traversal guard. */
class ProfileStoreTest {

    @TempDir
    Path tempDir;

    private ProfileStore store() {
        return new ProfileStore(tempDir);
    }

    @Test
    void roundTripsEveryField() throws Exception {
        Profile saved = new Profile();
        saved.name = "probe";
        saved.endpoint = "http://localhost:4566";
        saved.container = "floci-probe";
        saved.image = "floci/floci:enforced";
        saved.port = 4599;
        saved.persistDir = "/tmp/floci-persist-test";
        saved.services = "s3,lambda";
        saved.output = "json";
        store().save(saved);

        Profile loaded = store().get("probe").orElseThrow();
        assertEquals("probe", loaded.name);
        assertEquals("http://localhost:4566", loaded.endpoint);
        assertEquals("floci-probe", loaded.container);
        assertEquals("floci/floci:enforced", loaded.image);
        assertEquals(4599, loaded.port);
        assertEquals("/tmp/floci-persist-test", loaded.persistDir);
        assertEquals("s3,lambda", loaded.services);
        assertEquals("json", loaded.output);
    }

    @Test
    void newProfileTakesItsProductsDefaults() throws Exception {
        store().save(new Profile(ProductProfile.GCP, "g"));

        Profile loaded = store().get("g").orElseThrow();
        assertEquals("http://localhost:4588", loaded.endpoint);
        assertEquals("floci-gcp", loaded.container);
        assertEquals("floci/floci-gcp:latest", loaded.image);
        assertEquals(4588, loaded.port);
    }

    @Test
    void missingProfileIsEmptyAndDeleteReportsIt() throws Exception {
        assertTrue(store().get("absent").isEmpty());
        assertTrue(store().delete("absent").isEmpty());

        store().save(new Profile(ProductProfile.AWS, "here"));
        assertFalse(store().delete("here").isEmpty());
        assertTrue(store().delete("here").isEmpty());
    }

    @Test
    void readsTheYmlSpellingThatListHasAlwaysAccepted() throws Exception {
        Files.writeString(tempDir.resolve("staging.yml"), "container: floci-staging\n");

        assertEquals("floci-staging", store().get("staging").orElseThrow().container);
        assertFalse(store().delete("staging").isEmpty());
    }

    @Test
    void listBackfillsTheNameFromTheFileName() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("handwritten.yaml"), "container: floci-hand\n");

        List<Profile> profiles = store().list();
        assertEquals(1, profiles.size());
        assertEquals("handwritten", profiles.get(0).name);
    }

    /** BL-011: deleting only the preferred spelling left the other one answering --profile. */
    @Test
    void deleteRemovesEverySpelling() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("dev.yaml"), "port: 4599\n");
        Files.writeString(tempDir.resolve("dev.yml"), "port: 4600\n");

        assertEquals(2, store().delete("dev").size());
        assertTrue(store().get("dev").isEmpty());
    }

    @Test
    void listSkipsFilesNoCommandCanAddress() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve(".yaml"), "port: 4599\n");
        Files.writeString(tempDir.resolve("ok.yaml"), "port: 4599\n");

        assertEquals(List.of("ok"), store().list().stream().map(p -> p.name).toList());
    }

    @Test
    void existingFileIsTheSpellingOnDisk() throws Exception {
        Files.createDirectories(tempDir);
        assertEquals(tempDir.resolve("dev.yaml").toAbsolutePath(), store().existingFile("dev").toAbsolutePath());

        Files.writeString(tempDir.resolve("dev.yml"), "port: 4599\n");
        assertEquals(tempDir.resolve("dev.yml").toAbsolutePath(), store().existingFile("dev").toAbsolutePath());
    }

    @Test
    void getBackfillsTheNameAsListDoes() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("handwritten.yml"), "container: floci-hand\n");

        assertEquals("handwritten", store().get("handwritten").orElseThrow().name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ok", "ok-1", "ok_1", "ok.1", "OK"})
    void acceptsOrdinaryNames(String name) {
        assertEquals(name, ProfileStore.validateName(name));
    }

    /**
     * 0.2.1 resolved the raw name, so every one of these was creatable. An allow-list on the
     * character set orphaned them: listed by 'config profile list', rejected by show, --profile
     * and delete, with no way to remove them through the CLI.
     */
    @ParameterizedTest
    @ValueSource(strings = {"team alpha", "prod+eu", "dev@local", "staging(1)", "a b", "sam's"})
    void keepsAcceptingNamesEarlierVersionsAllowed(String name) {
        assertEquals(name, ProfileStore.validateName(name));
    }

    @Test
    void aLegacyNameRoundTripsAndCanBeDeleted() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("team alpha.yaml"), "container: floci-team\n");

        assertEquals("floci-team", store().get("team alpha").orElseThrow().container);
        assertFalse(store().delete("team alpha").isEmpty());
        assertTrue(store().get("team alpha").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "../x", "../../etc/passwd", "a/b", "/abs"})
    void rejectsNamesThatCouldEscapeTheProfilesDirectory(String name) {
        assertThrows(IllegalArgumentException.class, () -> ProfileStore.validateName(name), name);
    }

    /**
     * Backslash is a separator on Windows and an ordinary character on Unix, so 0.2.1 could
     * create 'team\\alpha.yaml' here and list() still returns it. Denying it outright would
     * orphan that profile exactly the way the character allow-list did. The escape check is
     * resolveInProfilesDir, which rejects a real Windows traversal on the parent assertion.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void backslashIsAnOrdinaryCharacterOnUnix() throws Exception {
        assertEquals("team\\alpha", ProfileStore.validateName("team\\alpha"));

        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("team\\alpha.yaml"), "container: floci-team\n");
        assertEquals("floci-team", store().get("team\\alpha").orElseThrow().container);
        assertEquals(tempDir.toAbsolutePath().normalize(),
                store().profileFile("team\\alpha").getParent());
        assertFalse(store().delete("team\\alpha").isEmpty());
    }

    @Test
    void rejectsNullName() {
        assertThrows(IllegalArgumentException.class, () -> ProfileStore.validateName(null));
    }

    @Test
    void traversalNeverEscapesTheProfilesDirectory() {
        assertThrows(IllegalArgumentException.class, () -> store().profileFile("../../escaped"));
        assertThrows(IllegalArgumentException.class, () -> store().delete("../../escaped"));
    }

    /** The guarantee is the resolved parent, not the character rules. */
    @Test
    void everyResolvedFileSitsDirectlyInTheProfilesDirectory() {
        for (String name : new String[]{"ok", "team alpha", "prod+eu", "dev@local", "..leading"}) {
            assertEquals(tempDir.toAbsolutePath().normalize(),
                    store().profileFile(name).getParent(), name);
        }
    }

    /** BL-002: the message names what the user typed, not the file it would have become. */
    @Test
    void errorsQuoteTheNameAsTyped() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store().profileFile("..\u0000x"));
        assertTrue(e.getMessage().contains("'..\u0000x'"), e.getMessage());
        assertFalse(e.getMessage().contains(".yaml"), e.getMessage());
    }

    @Test
    void unknownKeysAreRecordedButNeverWrittenBack() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("typo.yaml"), "name: typo\npersist_dir: /data\nport: 4599\n");

        Profile p = store().get("typo").orElseThrow();
        assertEquals(List.of("persist_dir"), p.unknownKeys());
        assertEquals(4599, p.port);

        store().save(p);
        assertFalse(Files.readString(tempDir.resolve("typo.yaml")).contains("persist_dir"));
        assertFalse(Files.readString(tempDir.resolve("typo.yaml")).contains("unknownKeys"));
    }

    @Test
    void knownKeysAreExactlyTheProfileFields() {
        List<String> fields = Arrays.stream(Profile.class.getFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .sorted().toList();
        assertEquals(fields, Profile.KNOWN_KEYS.stream().sorted().toList());
    }

    /** BL-042: saving goes through a temporary file, and none is left behind. */
    @Test
    void saveLeavesNoTemporaryFiles() throws Exception {
        store().save(new Profile(ProductProfile.AWS, "one"));
        store().save(new Profile(ProductProfile.AWS, "one"));

        try (var files = Files.list(tempDir)) {
            assertEquals(List.of("one.yaml"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    /** BL-044: an unreadable profile is skipped by list, but reported. */
    @Test
    void listReportsAProfileItCannotRead() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("broken.yaml"), "port: [unterminated\n");
        Files.writeString(tempDir.resolve("fine.yaml"), "port: 4599\n");
        PrintStream err = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuf));
        List<Profile> listed;
        try {
            listed = store().list();
        } finally {
            System.setErr(err);
        }

        assertEquals(List.of("fine"), listed.stream().map(p -> p.name).toList());
        assertTrue(errBuf.toString().contains("broken.yaml"), errBuf.toString());
    }

    /** BL-042: a save whose final move fails removes its temporary file and leaves the target alone. */
    @Test
    void aFailedSaveRemovesTheTemporaryFile() throws Exception {
        Path inTheWay = Files.createDirectories(tempDir.resolve("one.yaml"));
        Files.writeString(inTheWay.resolve("keep"), "untouched");

        assertThrows(IOException.class, () -> store().save(new Profile(ProductProfile.AWS, "one")));

        assertEquals("untouched", Files.readString(inTheWay.resolve("keep")));
        try (var files = Files.list(tempDir)) {
            assertEquals(List.of("one.yaml"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    /** The temporary file's name does not grow with the profile's, so a name at the limit still saves. */
    @Test
    void aNameAtTheFileNameLimitStillSaves() throws Exception {
        String name = "n".repeat(250); // + ".yaml" = 255 bytes, the usual file name limit

        store().save(new Profile(ProductProfile.AWS, name));

        assertEquals(name, store().get(name).orElseThrow().name);
    }

    /** A profile that cannot be opened is reported as that, with the cause, not as bad YAML. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void listReportsWhyAProfileCouldNotBeOpened() throws Exception {
        Files.createDirectories(tempDir);
        Path locked = tempDir.resolve("locked.yaml");
        Files.writeString(locked, "port: 4599\n");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(locked), "permissions do not bind this user (root)");
        PrintStream err = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuf));
        List<Profile> listed;
        try {
            listed = store().list();
        } finally {
            System.setErr(err);
        }

        assertEquals(List.of(), listed);
        assertTrue(errBuf.toString().contains("Could not read profile file"), errBuf.toString());
        assertTrue(errBuf.toString().contains("locked.yaml"), errBuf.toString());
        assertTrue(errBuf.toString().contains("Permission denied"), errBuf.toString());
        assertFalse(errBuf.toString().contains("not valid YAML"), errBuf.toString());
    }
}
