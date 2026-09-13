package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins issue #22: --profile must reach every command, and the command line must still win.
 * Precedence is CLI flag &gt; profile &gt; environment variable &gt; product default.
 */
class ProfilePrecedenceTest {

    @TempDir
    Path tempDir;

    private static final String FULL_PROFILE = """
            name: probe
            endpoint: http://localhost:4566
            container: floci-probe
            image: floci/floci:enforced
            port: 4599
            persistDir: /tmp/floci-persist-test
            services: s3,lambda
            output: json
            """;

    private void writeProfile(String name, String yaml) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve(name + ".yaml"), yaml);
    }

    private CommandSpec leafSpec(String... args) {
        ParseResult r = FlociCli.buildCommandLine(new ProfileStore(tempDir)).parseArgs(args);
        while (r.hasSubcommand()) r = r.subcommand();
        return r.commandSpec();
    }

    @Test
    void profileSuppliesEveryStartOption() throws Exception {
        writeProfile("probe", FULL_PROFILE);
        CommandSpec spec = leafSpec("start", "--profile", "probe");

        assertEquals("http://localhost:4566", spec.findOption("--endpoint").getValue());
        assertEquals("floci-probe", spec.findOption("--container").getValue());
        assertEquals("floci/floci:enforced", spec.findOption("--image").getValue());
        assertEquals(Integer.valueOf(4599), spec.findOption("--port").getValue());
        assertEquals("/tmp/floci-persist-test", spec.findOption("--persist").getValue());
        assertEquals("s3,lambda", spec.findOption("--services").getValue());
        assertEquals("json", spec.findOption("--output").getValue().toString());
    }

    @Test
    void commandLineFlagsBeatTheProfile() throws Exception {
        writeProfile("probe", FULL_PROFILE);
        CommandSpec spec = leafSpec("start", "--profile", "probe",
                "--container", "explicit", "--image", "explicit:1", "--port", "1234",
                "--persist", "/explicit", "--services", "sqs", "--endpoint", "http://explicit:1", "-o", "yaml");

        assertEquals("http://explicit:1", spec.findOption("--endpoint").getValue());
        assertEquals("explicit", spec.findOption("--container").getValue());
        assertEquals("explicit:1", spec.findOption("--image").getValue());
        assertEquals(Integer.valueOf(1234), spec.findOption("--port").getValue());
        assertEquals("/explicit", spec.findOption("--persist").getValue());
        assertEquals("sqs", spec.findOption("--services").getValue());
        assertEquals("yaml", spec.findOption("--output").getValue().toString());
    }

    @Test
    void fieldsTheProfileOmitsKeepTheProductDefault() throws Exception {
        writeProfile("partial", "container: floci-partial\n");
        CommandSpec spec = leafSpec("start", "--profile", "partial");

        assertEquals("floci-partial", spec.findOption("--container").getValue());
        assertEquals("floci/floci:latest", spec.findOption("--image").getValue());
        assertEquals(Integer.valueOf(4566), spec.findOption("--port").getValue());
        assertNull(spec.findOption("--persist").getValue());
        assertEquals("text", spec.findOption("--output").getValue().toString());
    }

    @Test
    void everyProductTreeAppliesTheProfileAndKeepsItsOwnDefaults() throws Exception {
        writeProfile("partial", "container: floci-partial\n");
        String[][] trees = {
                {"aws", "4566", "floci/floci:latest"},
                {"gcp", "4588", "floci/floci-gcp:latest"},
                {"az", "4577", "floci/floci-az:latest"},
                {"oci", "4599", "floci/floci-oci:latest"},
        };
        for (String[] tree : trees) {
            CommandSpec spec = leafSpec(tree[0], "start", "--profile", "partial");
            assertEquals("floci-partial", spec.findOption("--container").getValue(), tree[0]);
            assertEquals(Integer.valueOf(tree[1]), spec.findOption("--port").getValue(), tree[0]);
            assertEquals(tree[2], spec.findOption("--image").getValue(), tree[0]);
        }
    }

    @Test
    void profileAppliesToCommandsOtherThanStart() throws Exception {
        writeProfile("probe", FULL_PROFILE);
        // 'env' is one of the commands that does not pre-initialize its GlobalOptions mixin.
        for (String command : new String[]{"status", "logs", "wait", "doctor", "env", "services"}) {
            CommandSpec spec = leafSpec(command, "--profile", "probe");
            assertEquals("floci-probe", spec.findOption("--container").getValue(), command);
            assertEquals("http://localhost:4566", spec.findOption("--endpoint").getValue(), command);
        }
    }

    @Test
    void singularServiceFilterIsNotTheProfilesServicesList() throws Exception {
        writeProfile("probe", FULL_PROFILE);
        assertNull(leafSpec("wait", "--profile", "probe").findOption("--service").getValue());
        assertNull(leafSpec("logs", "--profile", "probe").findOption("--service").getValue());
    }

    @Test
    void withoutAProfileNothingChanges() {
        CommandSpec spec = leafSpec("start");
        assertEquals("floci", spec.findOption("--container").getValue());
        assertEquals("floci/floci:latest", spec.findOption("--image").getValue());
        assertEquals(Integer.valueOf(4566), spec.findOption("--port").getValue());
        assertNull(spec.findOption("--persist").getValue());
    }

    @Test
    void commandsWithoutGlobalOptionsAreUntouched() {
        // 'update' has no GlobalOptions mixin at all, so the provider must bail out rather
        // than dereference a missing --profile option.
        assertEquals(Boolean.TRUE,
                leafSpec("update", "--check").findOption("--check").getValue());
    }

    @Test
    void picocliInterpolatesProfileValues() throws Exception {
        // Documented consequence of routing values through picocli's default-value machinery.
        writeProfile("interp", "persistDir: ${env:HOME}/floci-data\n");
        assertEquals(System.getenv("HOME") + "/floci-data",
                leafSpec("start", "--profile", "interp").findOption("--persist").getValue());
    }
}
