package io.floci.cli.unit;

import com.sun.net.httpserver.HttpServer;
import io.floci.cli.FlociCli;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** BL-048: the snapshot commands against a server that has no snapshot API (404). */
class SnapshotCommandsTest {

    @TempDir
    Path tempDir;

    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String[] run(String... args) {
        String[] all = new String[args.length + 4];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = "--endpoint";
        all[args.length + 1] = "http://127.0.0.1:" + server.getAddress().getPort();
        all[args.length + 2] = "--container";
        all[args.length + 3] = "floci-snapshot-test-none";
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf));
        System.setErr(new PrintStream(errBuf));
        try {
            int exit = FlociCli.buildCommandLine(new ProfileStore(tempDir)).execute(all);
            return new String[]{String.valueOf(exit), outBuf.toString(), errBuf.toString()};
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    @Test
    void deleteSaysTheApiIsMissingLikeSaveAndLoad() {
        String[] r = run("snapshot", "delete", "nightly");

        assertEquals("1", r[0]);
        assertTrue(r[2].contains("Snapshot API not available"), r[2]);
    }

    @Test
    void aMessageIsNotDroppedSilently() {
        String[] r = run("snapshot", "save", "nightly", "-m", "before upgrade");

        assertTrue(r[2].contains("--message is not sent"), r[2]);
    }
}
