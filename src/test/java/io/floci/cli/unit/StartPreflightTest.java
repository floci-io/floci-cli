package io.floci.cli.unit;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StartCommand;
import io.floci.cli.docker.DockerClient;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** BL-058: start reports a busy host port itself, before pulling or running anything. */
class StartPreflightTest {

    @Test
    void aBusyPortStopsTheStartWithAHint() throws Exception {
        assumeTrue(DockerClient.dockerHost().kind() != DockerClient.Kind.TCP, "a remote daemon skips the local probe");
        AtomicBoolean pulled = new AtomicBoolean();
        DockerClient docker = new DockerClient() {
            @Override
            public Optional<ContainerInfo> inspectContainer(String name) {
                return Optional.empty();
            }

            @Override
            public void pull(String image, String policy) {
                pulled.set(true);
            }
        };

        try (ServerSocket taken = new ServerSocket()) {
            taken.bind(new InetSocketAddress(0));
            int port = taken.getLocalPort();
            PrintStream out = System.out;
            PrintStream err = System.err;
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            System.setOut(new PrintStream(new ByteArrayOutputStream()));
            System.setErr(new PrintStream(errBuf));
            int exit;
            try {
                exit = new CommandLine(new StartCommand(ProductProfile.GCP, docker))
                        .execute("--port", String.valueOf(port), "--container", "floci-gcp-preflight");
            } finally {
                System.setOut(out);
                System.setErr(err);
            }

            assertEquals(1, exit);
            assertTrue(errBuf.toString().contains("Port " + port + " is already in use"), errBuf.toString());
            assertTrue(errBuf.toString().contains("--port <other>"), errBuf.toString());
            assertFalse(pulled.get(), "nothing is pulled for a start that cannot bind");
        }
    }
}
