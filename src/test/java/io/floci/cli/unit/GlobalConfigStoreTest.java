package io.floci.cli.unit;

import io.floci.cli.config.GlobalConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class GlobalConfigStoreTest {

    @TempDir
    Path tempDir;

    private GlobalConfigStore store() {
        return new GlobalConfigStore(tempDir.resolve("config.yaml"));
    }

    @Test
    void defaultsToAwsWithoutAFile() {
        assertEquals("aws", store().getDefaultProduct());
    }

    @Test
    void remembersTheProductItWasGiven() throws Exception {
        store().setDefaultProduct("gcp");

        assertEquals("gcp", store().getDefaultProduct());
    }

    /** BL-042: the write goes through a temporary file, and none is left behind. */
    @Test
    void writesLeaveNoTemporaryFiles() throws Exception {
        store().setDefaultProduct("oci");
        store().setDefaultProduct("az");

        try (var files = Files.list(tempDir)) {
            assertEquals(List.of("config.yaml"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    /** BL-043: a key this version does not know must not reset the default product. */
    @Test
    void anUnknownKeyKeepsTheProduct() throws Exception {
        Files.writeString(tempDir.resolve("config.yaml"), "default-product: az\nsome-future-key: true\n");

        assertEquals("az", store().getDefaultProduct());
    }

    /** BL-043: an unreadable file still falls back to aws, but says so. */
    @Test
    void anUnreadableFileWarnsAndFallsBackToAws() throws Exception {
        Files.writeString(tempDir.resolve("config.yaml"), "default-product: [unterminated\n");
        PrintStream err = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuf));
        String product;
        try {
            product = store().getDefaultProduct();
        } finally {
            System.setErr(err);
        }

        assertEquals("aws", product);
        assertTrue(errBuf.toString().contains("Could not read"), errBuf.toString());
        assertTrue(errBuf.toString().contains("floci config default-product"), errBuf.toString());
    }

    /** A config file the user may not look at is not a missing one: it warns like any failed read. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFileInADirectoryThatCannotBeSearchedWarns() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Files.writeString(home.resolve("config.yaml"), "default-product: gcp\n");
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("---------"));
        PrintStream err = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        String product;
        try {
            assumeFalse(Files.isExecutable(home), "permissions do not bind this user (root)");
            System.setErr(new PrintStream(errBuf));
            product = new GlobalConfigStore(home.resolve("config.yaml")).getDefaultProduct();
        } finally {
            System.setErr(err);
            Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
        }

        assertEquals("aws", product);
        assertTrue(errBuf.toString().contains("Could not read"), errBuf.toString());
        assertTrue(errBuf.toString().contains("permission denied"), errBuf.toString());
    }

    @Test
    void aMissingDirectoryIsSilentlyAws() {
        PrintStream err = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuf));
        String product;
        try {
            product = new GlobalConfigStore(tempDir.resolve("absent").resolve("config.yaml")).getDefaultProduct();
        } finally {
            System.setErr(err);
        }

        assertEquals("aws", product);
        assertEquals("", errBuf.toString());
    }

    /**
     * BL-042: a save that cannot complete leaves the old file byte for byte. An in-place write
     * would pass through a read-only directory and overwrite it.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFailedSaveKeepsTheOldFile() throws Exception {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, "default-product: gcp\n");
        Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("r-x------"));
        try {
            assumeFalse(Files.isWritable(tempDir), "permissions do not bind this user (root)");
            assertThrows(IOException.class, () -> store().setDefaultProduct("az"));
        } finally {
            Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwx------"));
        }

        assertEquals("default-product: gcp\n", Files.readString(config));
        try (var files = Files.list(tempDir)) {
            assertEquals(List.of("config.yaml"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    /** BL-042: when the final move fails, the temporary file is removed and the target untouched. */
    @Test
    void aFailedMoveRemovesTheTemporaryFile() throws Exception {
        Path inTheWay = Files.createDirectory(tempDir.resolve("config.yaml"));
        Files.writeString(inTheWay.resolve("keep"), "untouched");

        assertThrows(IOException.class, () -> store().setDefaultProduct("az"));

        assertEquals("untouched", Files.readString(inTheWay.resolve("keep")));
        try (var files = Files.list(tempDir)) {
            assertEquals(List.of("config.yaml"), files.map(p -> p.getFileName().toString()).toList());
        }
    }
}
