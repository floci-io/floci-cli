package io.floci.cli.unit;

import io.floci.cli.ProductProfile;
import io.floci.cli.config.Profile;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        assertFalse(store().delete("absent"));

        store().save(new Profile(ProductProfile.AWS, "here"));
        assertTrue(store().delete("here"));
        assertFalse(store().delete("here"));
    }

    @Test
    void readsTheYmlSpellingThatListHasAlwaysAccepted() throws Exception {
        Files.writeString(tempDir.resolve("staging.yml"), "container: floci-staging\n");

        assertEquals("floci-staging", store().get("staging").orElseThrow().container);
        assertTrue(store().delete("staging"));
    }

    @Test
    void listBackfillsTheNameFromTheFileName() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("handwritten.yaml"), "container: floci-hand\n");

        List<Profile> profiles = store().list();
        assertEquals(1, profiles.size());
        assertEquals("handwritten", profiles.get(0).name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ok", "ok-1", "ok_1", "ok.1", "OK"})
    void acceptsOrdinaryNames(String name) {
        assertEquals(name, ProfileStore.validateName(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "../x", "../../etc/passwd", "a/b", "a\\b", "/abs", "a:b"})
    void rejectsNamesThatCouldEscapeTheProfilesDirectory(String name) {
        assertThrows(IllegalArgumentException.class, () -> ProfileStore.validateName(name), name);
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
}
