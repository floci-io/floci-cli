package io.floci.cli.unit;

import io.floci.cli.ProductProfile;
import io.floci.cli.config.InstanceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InstanceStateTest {

    private static final Path ROOT = Path.of("/home/u/.floci");

    @Test
    void oneDirectoryPerProductAndContainer() {
        assertEquals(ROOT.resolve("az/floci-az"), InstanceState.dir(ROOT, ProductProfile.AZ, "floci-az"));
        assertEquals(ROOT.resolve("az/floci-az-b"), InstanceState.dir(ROOT, ProductProfile.AZ, "floci-az-b"));
        assertEquals(ROOT.resolve("gcp/floci-gcp"), InstanceState.dir(ROOT, ProductProfile.GCP, "floci-gcp"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "../x", "a/b", "a\\b"})
    void refusesNamesThatAreNotAContainerName(String name) {
        assertThrows(IllegalArgumentException.class, () -> InstanceState.dir(ROOT, ProductProfile.AZ, name));
    }
}
