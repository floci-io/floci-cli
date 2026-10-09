package io.floci.cli.unit;

import com.sun.net.httpserver.HttpServer;
import io.floci.cli.FlociCli;
import io.floci.cli.commands.WaitCommand;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** BL-030: wait polls soon after a start and never runs past --timeout. */
class WaitCommandTimingTest {

    @TempDir
    Path tempDir;

    @Test
    void pollingBacksOffFromTwentyFiveMillisToHalfASecond() {
        assertEquals(50, WaitCommand.nextDelay(25));
        assertEquals(100, WaitCommand.nextDelay(50));
        assertEquals(200, WaitCommand.nextDelay(100));
        assertEquals(400, WaitCommand.nextDelay(200));
        assertEquals(500, WaitCommand.nextDelay(400));
        assertEquals(500, WaitCommand.nextDelay(500));
    }

    @Test
    void aRequestNeverOutlivesTheDeadline() {
        assertEquals(Duration.ofSeconds(10), WaitCommand.requestTimeout(Duration.ofMinutes(2)));
        assertEquals(Duration.ofMillis(800), WaitCommand.requestTimeout(Duration.ofMillis(800)));
        assertEquals(Duration.ofMillis(1), WaitCommand.requestTimeout(Duration.ZERO));
        assertEquals(Duration.ofMillis(1), WaitCommand.requestTimeout(Duration.ofMillis(-5)));
    }

    @Test
    void aServerThatNeverAnswersStillTimesOutOnTime() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(5_000); // longer than the timeout below
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        PrintStream err = System.err;
        PrintStream out = System.out;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            long start = System.nanoTime();
            int exit = FlociCli.buildCommandLine(new ProfileStore(tempDir)).execute(
                    "wait", "--timeout", "1s", "--endpoint", endpoint, "--container", "floci-wait-timing-none");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(1, exit);
            // The old loop could block for a full 10 s request; allow docker's inspect on top.
            assertTrue(elapsedMs < 2_500, "took " + elapsedMs + " ms");
        } finally {
            System.setErr(err);
            System.setOut(out);
            server.stop(0);
        }
    }
}
