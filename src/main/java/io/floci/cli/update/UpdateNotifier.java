package io.floci.cli.update;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Console;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backing logic for the "a new version is available" hint, in the style of npm/gh/rustup.
 * Split in two halves so it never blocks or spams:
 * <ul>
 *   <li>{@link #pendingNotice} reads the last known latest version from a local cache
 *       ({@code ~/.floci/update-check.json}) — the end-of-command hint renders from this,
 *       no network.</li>
 *   <li>{@link #refreshInBackground} runs the actual check on a background virtual thread
 *       (fail-silent), only when the cache is older than 24h, updating it for next time.</li>
 * </ul>
 */
public final class UpdateNotifier {

    static final Duration TTL = Duration.ofHours(24);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The in-flight refresh, if this run started one; lets main() grant it a short grace. */
    private static final AtomicReference<Thread> REFRESH = new AtomicReference<>();

    private UpdateNotifier() {
    }

    /**
     * The newer version to announce at end-of-command, if any: cached latest strictly newer
     * than {@code currentVersion}. Pure cache read — the caller gates on
     * {@link #interactiveRunEnabled()}.
     */
    public static Optional<String> pendingNotice(String currentVersion) {
        if (currentVersion == null) {
            return Optional.empty();
        }
        return cachedLatest().filter(latest -> Version.isNewer(latest, currentVersion));
    }

    /** The latest version recorded by the last successful check, if any. Never fails. */
    public static Optional<String> cachedLatest() {
        try {
            Path path = cachePath();
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            return Optional.ofNullable(JSON.readValue(path.toFile(), UpdateCache.class).latestVersion());
        } catch (Exception _) {
            return Optional.empty();
        }
    }

    /** Kick a background refresh if the cache is stale/absent. Non-blocking and fail-silent;
     * the caller gates on {@link #interactiveRunEnabled()}. */
    public static void refreshInBackground() {
        refreshInBackground(ReleaseChannel.latestReleaseUrl());
    }

    /** Refresh from an explicit URL (the seam used by tests). */
    static void refreshInBackground(String latestReleaseUrl) {
        Optional<UpdateCache> cache = readQuietly();
        if (cache.isPresent() && !isStale(cache.get())) {
            return;
        }
        // Virtual threads do not keep the JVM alive: a quick exit just drops the check and it
        // is retried next time; main() grants a short grace via awaitRefresh so commands that
        // finish fast still get the cache populated eventually.
        REFRESH.set(Thread.ofVirtual().name("floci-update-check").start(() -> {
            try {
                String latest = fetchLatestVersion(latestReleaseUrl);
                Path path = cachePath();
                Files.createDirectories(path.getParent());
                JSON.writeValue(path.toFile(),
                        new UpdateCache(Instant.now().getEpochSecond(), latest));
            } catch (Exception _) {
                // Network/parse failure — never surfaced; the stale/absent cache stays as-is.
            }
        }));
    }

    /** Give an in-flight refresh a bounded chance to finish before the JVM exits. */
    public static void awaitRefresh(Duration grace) {
        Thread refresh = REFRESH.get();
        if (refresh != null) {
            try {
                refresh.join(grace);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Optional<UpdateCache> readQuietly() {
        try {
            Path path = cachePath();
            return Files.isRegularFile(path)
                    ? Optional.of(JSON.readValue(path.toFile(), UpdateCache.class))
                    : Optional.empty();
        } catch (Exception _) {
            return Optional.empty();
        }
    }

    private static boolean isStale(UpdateCache cache) {
        return Instant.ofEpochSecond(cache.checkedAtEpochSeconds()).plus(TTL).isBefore(Instant.now());
    }

    private static String fetchLatestVersion(String latestReleaseUrl) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(latestReleaseUrl))
                .timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        String version = JSON.readTree(resp.body()).path("tag_name").asText("");
        // Shape-check before persisting: a captive portal (hotel/airport WiFi) answers any URL
        // with HTTP 200 + HTML — never let that poison the cache.
        if (!Version.isValid(version)) {
            throw new IllegalStateException("response is not a version");
        }
        return version;
    }

    static Path cachePath() {
        return Path.of(System.getProperty("user.home"), ".floci", "update-check.json");
    }

    /**
     * Whether update UX (checks and notices) applies to this run at all: an interactive
     * terminal, not CI, not opted out. Piped invocations ({@code floci ... | jq}) are excluded —
     * their stdout must stay pure and even stderr noise is unwelcome in scripts.
     */
    public static boolean interactiveRunEnabled() {
        return !notBlank(System.getenv("FLOCI_NO_UPDATE_CHECK"))
                && !notBlank(System.getenv("CI"))
                && interactiveTerminal();
    }

    private static boolean interactiveTerminal() {
        // Java 22+ can return a Console even with redirected streams — isTerminal() is the
        // authoritative check (a null console is simply never a terminal).
        Console console = System.console();
        return console != null && console.isTerminal();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
