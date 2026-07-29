package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests pinning the CLI surface of all four product trees.
 * Written against the copied-tree implementation; they must keep passing
 * unchanged through the ProductProfile unification refactor.
 */
class ProductTreesParsingTest {

    private static final Set<String> LIFECYCLE_COMMANDS = Set.of(
            "start", "stop", "restart", "status", "logs", "wait",
            "version", "services", "doctor", "env", "config", "snapshot", "completion", "help");

    private static CommandSpec leafSpec(String... args) {
        ParseResult r = new CommandLine(new FlociCli()).parseArgs(args);
        while (r.hasSubcommand()) r = r.subcommand();
        return r.commandSpec();
    }

    private static void assertDefaults(String expectedEndpoint, String expectedContainer, String... args) {
        CommandSpec spec = leafSpec(args);
        assertEquals(expectedEndpoint, spec.findOption("--endpoint").getValue());
        assertEquals(expectedContainer, spec.findOption("--container").getValue());
    }

    private static void assertStartDefaults(String port, String image, String... args) {
        CommandSpec spec = leafSpec(args);
        assertEquals(Integer.valueOf(port), spec.findOption("--port").getValue());
        assertEquals(image, spec.findOption("--image").getValue());
    }

    @Test
    void awsTreeDefaults() {
        assertDefaults("http://localhost:4566", "floci", "status");
        assertDefaults("http://localhost:4566", "floci", "aws", "status");
        assertStartDefaults("4566", "floci/floci:latest", "start");
        assertStartDefaults("4566", "floci/floci:latest", "aws", "start");
    }

    @Test
    void gcpTreeDefaults() {
        assertDefaults("http://localhost:4588", "floci-gcp", "gcp", "status");
        assertStartDefaults("4588", "floci/floci-gcp:latest", "gcp", "start");
    }

    @Test
    void azTreeDefaults() {
        assertDefaults("http://localhost:4577", "floci-az", "az", "status");
        assertStartDefaults("4577", "floci/floci-az:latest", "az", "start");
    }

    @Test
    void ociTreeDefaults() {
        assertDefaults("http://localhost:4599", "floci-oci", "oci", "status");
        assertStartDefaults("4599", "floci/floci-oci:latest", "oci", "start");
    }

    @Test
    void endpointFlagOverridesDefaultInEveryTree() {
        for (String tree : new String[]{"aws", "gcp", "az", "oci"}) {
            CommandSpec spec = leafSpec(tree, "status", "--endpoint", "http://x:1");
            assertEquals("http://x:1", spec.findOption("--endpoint").getValue(), tree);
        }
    }

    @Test
    void everyProductGroupExposesTheFullCommandSet() {
        CommandLine root = new CommandLine(new FlociCli());
        for (String tree : new String[]{"aws", "gcp", "az", "oci"}) {
            Set<String> names = root.getSubcommands().get(tree).getSubcommands().keySet();
            assertTrue(names.containsAll(LIFECYCLE_COMMANDS),
                    tree + " is missing " + LIFECYCLE_COMMANDS.stream().filter(c -> !names.contains(c)).toList());
        }
        // oci additionally has setup; bare root additionally has update + the product groups
        assertTrue(root.getSubcommands().get("oci").getSubcommands().containsKey("setup"));
        assertTrue(root.getSubcommands().keySet().containsAll(Set.of("update", "aws", "gcp", "az", "oci")));
    }

    @Test
    void productConfigGroupsHaveNoDefaultProduct() {
        CommandLine root = new CommandLine(new FlociCli());
        assertTrue(root.getSubcommands().get("config").getSubcommands().containsKey("default-product"));
        for (String tree : new String[]{"gcp", "az", "oci"}) {
            Set<String> configSubs = root.getSubcommands().get(tree)
                    .getSubcommands().get("config").getSubcommands().keySet();
            assertTrue(configSubs.containsAll(Set.of("show", "validate", "profile")), tree);
            assertFalse(configSubs.contains("default-product"), tree);
        }
    }

    @Test
    void snapshotGroupsExposeAllLeaves() {
        CommandLine root = new CommandLine(new FlociCli());
        Set<String> expected = Set.of("save", "load", "list", "delete", "export", "import");
        assertTrue(root.getSubcommands().get("snapshot").getSubcommands().keySet().containsAll(expected));
        for (String tree : new String[]{"gcp", "az", "oci"}) {
            assertTrue(root.getSubcommands().get(tree)
                    .getSubcommands().get("snapshot").getSubcommands().keySet().containsAll(expected), tree);
        }
    }

    @Test
    void sharedFlagsParseInEveryTree() {
        for (String tree : new String[]{"aws", "gcp", "az", "oci"}) {
            CommandSpec spec = leafSpec(tree, "status", "-o", "json", "-q", "--no-color", "-v");
            assertEquals("json", spec.findOption("--output").getValue().toString());
            assertEquals(Boolean.TRUE, spec.findOption("--quiet").getValue());
            assertEquals(Boolean.TRUE, spec.findOption("--no-color").getValue());
            assertEquals(Boolean.TRUE, spec.findOption("--verbose").getValue());
        }
    }
}
