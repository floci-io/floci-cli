package io.floci.cli.unit;

import io.floci.cli.config.GlobalConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
}
