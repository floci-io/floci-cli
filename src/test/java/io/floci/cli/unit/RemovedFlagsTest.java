package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import picocli.CommandLine.UnmatchedArgumentException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BL-051, BL-052: doctor --fix fixed nothing and services --mode filtered nothing, so both were
 * removed rather than kept as promises. They stay rejected in every product tree.
 */
class RemovedFlagsTest {

    @TempDir
    Path tempDir;

    private CommandLine cli() {
        return FlociCli.buildCommandLine(new ProfileStore(tempDir));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws", "gcp", "az", "oci"})
    void doctorHasNoFixFlag(String product) {
        assertThrows(UnmatchedArgumentException.class, () -> cli().parseArgs(product, "doctor", "--fix"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws", "gcp", "az", "oci"})
    void servicesHasNoModeFlag(String product) {
        assertThrows(UnmatchedArgumentException.class, () -> cli().parseArgs(product, "services", "--mode", "docker"));
    }
}
