package io.floci.cli.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.cli.FlociCli;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BL-075: the CLI against the real emulator images, through the same command tree a user runs.
 * Opt-in (needs Docker and the images): {@code mvn test -Pintegration}.
 *
 * <p>Every container gets a unique name and a free host port, so the suite never touches a
 * developer's own instances, and each test removes what it started.
 */
@Tag("integration")
class EmulatorLifecycleIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private final List<String> started = new ArrayList<>();

    private record Run(int exit, String out, String err) {
        JsonNode json() {
            try {
                return JSON.readTree(out);
            } catch (IOException e) {
                throw new AssertionError("not JSON: " + out + "\nstderr: " + err, e);
            }
        }
    }

    @AfterEach
    void removeWhatWasStarted() throws Exception {
        for (String container : started) {
            new ProcessBuilder("docker", "rm", "-f", "--", container)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor();
        }
    }

    private Run floci(String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            int exit = FlociCli.buildCommandLine(new ProfileStore(tempDir)).execute(args);
            return new Run(exit, outBuf.toString(), errBuf.toString());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    // The product's tree as args: AWS is the root tree.
    private static String[] cmd(String product, String... rest) {
        List<String> all = new ArrayList<>();
        if (!product.equals("aws")) all.add(product);
        all.addAll(List.of(rest));
        return all.toArray(String[]::new);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String startInstance(String product, String defaultContainer, int port) {
        String container = defaultContainer + "-it" + UUID.randomUUID().toString().substring(0, 6);
        started.add(container);
        Run start = floci(cmd(product, "start", "--container", container, "--port", String.valueOf(port), "--detach"));
        assertEquals(0, start.exit(), "start " + container + ": " + start.err());
        Run wait = floci(cmd(product, "wait", "--container", container, "--timeout", "120s"));
        assertEquals(0, wait.exit(), "wait " + container + ": " + wait.err());
        return container;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"aws, floci", "gcp, floci-gcp", "az, floci-az", "oci, floci-oci"})
    void startWaitStatusEnvStop(String product, String defaultContainer) throws Exception {
        int port = freePort();
        String container = startInstance(product, defaultContainer, port);

        JsonNode status = floci(cmd(product, "status", "--container", container, "-o", "json")).json();
        assertEquals("running", status.path("state").asText(), status.toString());
        assertTrue(status.path("reachable").asBoolean(), status.toString());
        assertTrue(status.path("endpoint").asText().endsWith(":" + port), status.toString());

        Run env = floci(cmd(product, "env", "--container", container, "-o", "json"));
        assertEquals(0, env.exit(), env.err());
        assertTrue(env.out().contains(String.valueOf(port)), "env names the instance's port: " + env.out());

        Run stop = floci(cmd(product, "stop", "--container", container, "--remove"));
        assertEquals(0, stop.exit(), stop.err());
        JsonNode after = floci(cmd(product, "status", "--container", container, "-o", "json")).json();
        assertNotEquals("running", after.path("state").asText(), after.toString());
    }

    /** Two instances of one product side by side: each is found by its container, on its own port. */
    @Test
    void twoInstancesRunSideBySide() throws Exception {
        int portA = freePort();
        String a = startInstance("aws", "floci", portA);
        int portB = freePort();
        String b = startInstance("aws", "floci", portB);

        assertTrue(floci("status", "--container", a, "-o", "json").json().path("endpoint").asText().endsWith(":" + portA));
        assertTrue(floci("status", "--container", b, "-o", "json").json().path("endpoint").asText().endsWith(":" + portB));

        assertEquals(0, floci("stop", "--container", a, "--remove").exit());
        JsonNode still = floci("status", "--container", b, "-o", "json").json();
        assertTrue(still.path("reachable").asBoolean(), "stopping one instance leaves the other up: " + still);
    }
}
