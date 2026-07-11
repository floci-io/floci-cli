package io.floci.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import io.floci.cli.update.ReleaseChannel;
import io.floci.cli.update.Version;
import picocli.CommandLine.*;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Callable;

/**
 * Self-update: downloads the target release binary from the public GitHub repository
 * (same source as installer/install.sh), verifies its sha256 against the release's
 * {@code sha256sums.txt}, and atomically replaces the running binary.
 * <p>
 * The replacement uses the classic self-update trick: a running executable cannot be
 * written over, but its <em>path</em> can be atomically renamed onto — the running
 * process keeps executing the old inode, and the next invocation picks up the new
 * binary. The new file is staged in the <em>same directory</em> (same filesystem) so
 * the final {@code ATOMIC_MOVE} can never leave a half-written binary.
 * </p>
 */
@Command(
        name = "update",
        description = "Update floci to the latest release"
)
public class UpdateCommand implements Callable<Integer> {

    @Option(names = "--check",
            description = "Only report whether an update is available (exit 0: up to date, 1: update available)")
    boolean check;

    // No mixinStandardHelpOptions here: its -V/--version would collide with this option.
    @Option(names = "--version", paramLabel = "<version>", description = "Target version (default: latest release)")
    String to;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit")
    boolean help;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** The binary to replace; {@code null} means "resolve the running executable". Test seam. */
    Path selfPath;

    @Override
    public Integer call() {
        if (System.console() == null) {
            Ansi.disable();
        }
        Printer printer = new Printer(System.out, System.err, OutputFormat.text, false);
        try {
            String current = VersionCommand.CLI_VERSION;
            boolean pinned = to != null && !to.isBlank();
            String target = pinned ? to.trim() : fetchLatestVersion();

            // Unpinned updates are semantic, not string-equal: a pre-release/snapshot build
            // (0.1.9-rc.1) must not be silently downgraded to the latest stable (0.1.8).
            // Pinned --version keeps exact equality so deliberate downgrades still work.
            if (pinned ? target.equals(current) : !Version.isNewer(target, current)) {
                printer.println(Ansi.green("✓") + " floci " + current + " is up to date");
                return 0;
            }

            printer.println("update available: " + current + " → " + Ansi.bold(target));
            if (check) {
                printer.println(Ansi.gray("run 'floci update' to install it"));
                // Non-zero so scripts can gate on staleness: floci update --check || floci update
                return 1;
            }

            Path binary = resolveSelf();
            requireNotBrewManaged(binary);
            requireWritable(binary);

            String asset = "floci-" + detectPlatform();

            Path work = Files.createTempDirectory("floci-update");
            try {
                printer.println("downloading " + asset + " " + target + "...");
                Path fresh = work.resolve(asset);
                fetchFile(ReleaseChannel.assetUrl(target, asset), fresh);
                verifyChecksum(fresh, asset, fetchString(ReleaseChannel.assetUrl(target, "sha256sums.txt")));
                printer.println(Ansi.green("✓") + " checksum verified");
                replaceAtomically(fresh, binary);
            } finally {
                deleteRecursively(work);
            }

            printer.println(Ansi.green("✓") + " updated: " + current + " → " + target
                    + Ansi.gray("  (" + binary + ")"));
            return 0;
        } catch (UpdateException e) {
            printer.error(e.getMessage());
            return 1;
        } catch (Exception e) {
            printer.error("update failed: " + e.getMessage());
            return 1;
        }
    }

    // ── version resolution ───────────────────────────────────────────────────

    private String fetchLatestVersion() {
        try {
            JsonNode release = JSON.readTree(fetchString(ReleaseChannel.latestReleaseUrl()));
            String tag = release.path("tag_name").asText("");
            // Same shape-check as UpdateNotifier: a captive portal or proxy error page must
            // not end up spliced into a download URL.
            if (!Version.isValid(tag)) {
                throw new IOException("GitHub API response did not contain a release version");
            }
            return tag;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("update check interrupted");
        } catch (Exception e) {
            String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new UpdateException("cannot determine the latest version (" + cause + ").\n"
                    + "GitHub may be unreachable (offline?), or rate-limiting this address —\n"
                    + "pin a version with: floci update --version <version>");
        }
    }

    // ── self-location ────────────────────────────────────────────────────────

    private Path resolveSelf() {
        if (selfPath != null) {
            return selfPath;
        }
        String cmd = ProcessHandle.current().info().command()
                .orElseThrow(() -> new UpdateException("cannot locate the running executable"));
        Path path = Path.of(cmd);
        String file = path.getFileName().toString();
        if (file.equals("java") || file.equals("java.exe")) {
            throw new UpdateException("running from a jar (java -jar), not the native binary — "
                    + "nothing to self-update. Reinstall instead: curl -fsSL https://floci.io/install.sh | sh");
        }
        return path;
    }

    /**
     * A Homebrew install must be updated through brew: overwriting the Cellar binary
     * would succeed silently (it's user-writable) but desync brew's bookkeeping, and
     * the next {@code brew upgrade} would clobber it.
     */
    private static void requireNotBrewManaged(Path binary) {
        Path real;
        try {
            real = binary.toRealPath();
        } catch (IOException e) {
            real = binary.toAbsolutePath();
        }
        for (Path segment : real) {
            String name = segment.toString();
            if (name.equals("Cellar") || name.equals("homebrew") || name.equals(".linuxbrew")) {
                throw new UpdateException("this floci was installed with Homebrew — update it with:\n"
                        + "  brew upgrade floci");
            }
        }
    }

    private static void requireWritable(Path binary) {
        // The atomic rename replaces a directory entry — it only needs write access to the
        // parent directory, never to the binary itself (which installers often leave 555).
        Path dir = binary.getParent();
        if (dir == null || !Files.isWritable(dir)) {
            throw new UpdateException("no write permission for " + dir + " — run: sudo floci update");
        }
    }

    // ── platform (mirrors installer/install.sh) ──────────────────────────────

    static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String o = null;
        if (os.contains("mac")) {
            o = "darwin";
        } else if (os.contains("linux")) {
            o = "linux";
        } else if (os.contains("windows")) {
            throw new UpdateException("self-update is not supported on Windows — "
                    + "re-run the installer: irm https://floci.io/install.ps1 | iex");
        }
        String a = switch (arch) {
            case "aarch64", "arm64" -> "arm64";
            case "x86_64", "amd64" -> "amd64";
            default -> null;
        };
        if (o == null || a == null) {
            throw new UpdateException("no published build for this platform (" + os + "/" + arch + ")");
        }
        return o + "-" + a;
    }

    // ── download + verify ────────────────────────────────────────────────────

    private String fetchString(String url) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(get(url), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("GET " + url + " → HTTP " + resp.statusCode());
        }
        if (resp.body().isBlank()) {
            // GitHub's CDN can intermittently return 200 with an empty body (see install.sh).
            throw new IOException("GET " + url + " returned an empty body — please retry");
        }
        return resp.body();
    }

    private void fetchFile(String url, Path dest) throws IOException, InterruptedException {
        HttpResponse<Path> resp = http.send(get(url), HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() != 200) {
            throw new UpdateException("download failed: " + url + " → HTTP " + resp.statusCode()
                    + (resp.statusCode() == 404 ? " (does that release exist?)" : ""));
        }
        if (Files.size(dest) == 0) {
            // GitHub's CDN can intermittently hand back a 0-byte body (see install.sh).
            throw new UpdateException("download failed: " + url + " returned an empty body — please retry");
        }
    }

    private HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .GET().build();
    }

    private static void verifyChecksum(Path file, String asset, String checksums)
            throws IOException, java.security.NoSuchAlgorithmException {
        // Entries are CI artifact paths like "./floci-darwin-arm64/floci-darwin-arm64",
        // so match on the basename (install.sh does the same with an end-of-line grep).
        String expected = checksums.lines()
                .map(l -> l.trim().split("\\s+"))
                .filter(p -> p.length == 2 && p[1].substring(p[1].lastIndexOf('/') + 1).equals(asset))
                .map(p -> p[0])
                .findFirst()
                .orElseThrow(() -> new UpdateException(asset + " not found in sha256sums.txt — aborting"));
        // Stream the digest — the binary is tens of MB and must not be held in heap whole.
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        try (var in = new DigestInputStream(
                new BufferedInputStream(Files.newInputStream(file)), sha256)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        String actual = HexFormat.of().formatHex(sha256.digest());
        if (!actual.equals(expected)) {
            throw new UpdateException("checksum mismatch for " + asset + "\n  expected: " + expected
                    + "\n  actual:   " + actual + "\nThe download may be corrupt or tampered with. Aborting.");
        }
    }

    // ── atomic replace ───────────────────────────────────────────────────────

    private static void replaceAtomically(Path fresh, Path target) throws IOException {
        // Stage in the TARGET's directory (same filesystem), then atomically rename over it.
        // The running process keeps its old inode; the path now serves the new binary.
        Path staged = target.resolveSibling("." + target.getFileName() + ".new");
        try {
            Files.copy(fresh, staged, StandardCopyOption.REPLACE_EXISTING);
            if (staged.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Don't leave .<binary>.new debris next to the production binary — the temp-dir
            // cleanup in call() never sees this file.
            try {
                Files.deleteIfExists(staged);
            } catch (IOException _) {
                // best effort; the original failure is what matters
            }
            throw e;
        }
    }

    private static void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException _) {
                    // best-effort temp cleanup — a leftover temp dir is harmless
                }
            });
        } catch (IOException _) {
            // best-effort temp cleanup — a leftover temp dir is harmless
        }
    }

    /** Expected failure with a user-facing message (no stack trace). */
    private static final class UpdateException extends RuntimeException {
        UpdateException(String message) {
            super(message);
        }
    }
}
