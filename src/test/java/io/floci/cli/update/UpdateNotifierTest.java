package io.floci.cli.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** BL-026: a failed check is recorded too, so the next run does not retry and wait again. */
class UpdateNotifierTest {

    @TempDir
    Path home;

    private String realHome;

    @BeforeEach
    void pointHomeAtATempDir() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", realHome);
    }

    @Test
    void aFailedCheckRefreshesTheTimestampAndKeepsTheLastKnownVersion() throws Exception {
        Path cache = home.resolve(".floci").resolve("update-check.json");
        Files.createDirectories(cache.getParent());
        long stale = Instant.now().minus(Duration.ofDays(3)).getEpochSecond();
        Files.writeString(cache, "{\"checkedAtEpochSeconds\":" + stale + ",\"latestVersion\":\"0.2.0\"}");

        // Port 9 refuses connections, so the fetch fails at once.
        UpdateNotifier.refreshInBackground("http://127.0.0.1:9/releases/latest");
        UpdateNotifier.awaitRefresh(Duration.ofSeconds(5));

        UpdateCache written = new ObjectMapper().readValue(cache.toFile(), UpdateCache.class);
        assertEquals("0.2.0", written.latestVersion());
        assertTrue(written.checkedAtEpochSeconds() > stale + 60, "the attempt was recorded");
    }
}
