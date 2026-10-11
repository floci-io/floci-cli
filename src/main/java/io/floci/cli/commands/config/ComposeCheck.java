package io.floci.cli.commands.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.cli.ProductProfile;
import io.floci.cli.doctor.checks.ImageVersionCheck;
import io.floci.cli.update.Version;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What {@code config validate} finds in a Compose file. It reads the parsed document and looks
 * at the one service that runs the product's emulator, so a port or a volume on some other
 * service is never taken for the emulator's.
 */
public final class ComposeCheck {

    /** The directory the emulator keeps its state in, inside the container. */
    static final String DATA_DIR = "/app/data";

    private ComposeCheck() {}

    /**
     * The findings for one file. {@code service} is the Compose service that was checked, or null
     * when none could be told apart as the emulator's.
     */
    public record Report(String service, List<String> errors, List<String> warnings) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /** A volume entry, from either the short {@code src:dst[:mode]} or the long form. */
    record Mount(String source, String target, boolean readOnly) {}

    /**
     * @param baseDir the Compose file's directory, against which relative bind sources resolve
     */
    public static Report analyze(JsonNode compose, ProductProfile profile, boolean dockerSockRequired,
                                 String dockerSockNeededFor, Path baseDir) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JsonNode services = compose.path("services");
        if (!services.isObject() || services.isEmpty()) {
            errors.add("No services found: a Compose file needs a 'services' section with the "
                    + profile.displayName() + " service in it");
            return new Report(null, errors, warnings);
        }

        String ghcrImage = "ghcr.io/floci-io/" + profile.image().substring(profile.image().indexOf('/') + 1);
        Map.Entry<String, JsonNode> found = null;
        for (Map.Entry<String, JsonNode> service : services.properties()) {
            String repository = repositoryOf(service.getValue().path("image").asText(""));
            if (repository.equals(profile.image()) || repository.equals("docker.io/" + profile.image())
                    || repository.equals(ghcrImage)) {
                found = service;
                break;
            }
        }
        if (found == null) {
            warnings.add(profile.displayName() + " image reference not detected — ensure your service uses "
                    + profile.image() + " or " + ghcrImage);
            // With one service there is no doubt which one is meant (a locally built image, a
            // mirror); with several, checking the wrong one would only mislead.
            if (services.size() != 1) {
                return new Report(null, errors, warnings);
            }
            found = services.properties().iterator().next();
        }

        JsonNode service = found.getValue();
        List<Mount> mounts = mounts(service.path("volumes"));
        Map<String, String> environment = environment(service.path("environment"));
        int port = profile.defaultPort();

        if (mounts.stream().noneMatch(m -> endsWithDockerSock(m.source()) || endsWithDockerSock(m.target()))) {
            (dockerSockRequired ? errors : warnings)
                    .add("Missing /var/run/docker.sock volume mount — required for " + dockerSockNeededFor);
        }

        // With host networking the container's ports are the host's; there is nothing to map.
        if (!"host".equals(service.path("network_mode").asText("")) && !publishes(service.path("ports"), port)) {
            errors.add("No port mapping for " + port + " found — " + profile.displayName() + " listens on " + port
                    + " inside the container; map any host port to it, for example \"" + port + ":" + port + "\"");
        }

        Mount data = mounts.stream().filter(m -> DATA_DIR.equals(stripTrailingSlash(m.target()))).findFirst().orElse(null);
        String storageVar = profile.envVar("STORAGE_MODE");
        String storageMode = environment.get(storageVar);
        // A value Compose will substitute (${MODE:-persistent}) cannot be judged from here.
        boolean storageKnown = storageMode == null || !storageMode.contains("${");
        if (data != null && storageKnown && !"persistent".equalsIgnoreCase(storageMode)) {
            warnings.add("A volume is mounted at " + DATA_DIR + " but " + storageVar + " is not 'persistent' — "
                    + "the emulator keeps its state in memory by default, so the volume stays empty");
        }
        if (data == null && "persistent".equalsIgnoreCase(storageMode)) {
            warnings.add(storageVar + " is 'persistent' but nothing is mounted at " + DATA_DIR
                    + " — the state is lost when the container is removed");
        }
        if (data != null) {
            if (data.readOnly()) {
                errors.add("The volume at " + DATA_DIR + " is mounted read-only — the emulator cannot save its state there");
            }
            String notADirectory = fileWhereADirectoryIsNeeded(data.source(), baseDir);
            if (notADirectory != null) {
                errors.add("The volume at " + DATA_DIR + " is bound to " + notADirectory + ", which is a file, not a directory");
            }
        }

        String tag = tagOf(service.path("image").asText(""));
        // The minimum is the AWS emulator's; the other emulators are versioned on their own lines.
        if ("aws".equals(profile.name()) && Version.isValid(tag)
                && !ImageVersionCheck.meetsMinimum(tag, ImageVersionCheck.MIN_VERSION)) {
            warnings.add("Image tag " + tag + " is older than " + ImageVersionCheck.MIN_VERSION
                    + ", the oldest version this CLI supports — use a newer tag");
        }

        return new Report(found.getKey(), errors, warnings);
    }

    /** {@code registry/name:tag@digest} without its tag and digest. */
    public static String repositoryOf(String image) {
        String reference = image.strip();
        int digest = reference.indexOf('@');
        if (digest >= 0) reference = reference.substring(0, digest);
        int colon = reference.lastIndexOf(':');
        // A colon before the last slash is a registry port, not a tag.
        return colon > reference.lastIndexOf('/') ? reference.substring(0, colon) : reference;
    }

    /** The tag of an image reference, or an empty string when it has none. */
    public static String tagOf(String image) {
        String reference = image.strip();
        int digest = reference.indexOf('@');
        if (digest >= 0) reference = reference.substring(0, digest);
        int colon = reference.lastIndexOf(':');
        return colon > reference.lastIndexOf('/') ? reference.substring(colon + 1) : "";
    }

    /**
     * Whether one of a service's {@code ports} entries reaches {@code port} inside the container,
     * whatever the host side is: {@code 4566}, {@code "4566:4566"}, {@code "5566:4566"},
     * {@code "127.0.0.1:5566:4566/tcp"}, a range that contains it, or the long form's {@code target}.
     */
    public static boolean publishes(JsonNode ports, int port) {
        for (JsonNode entry : ports) {
            if (entry.isObject()) {
                if (entry.path("target").asInt(-1) == port) return true;
                continue;
            }
            String text = entry.asText("").strip();
            int protocol = text.indexOf('/');
            if (protocol >= 0) text = text.substring(0, protocol);
            String container = text.substring(text.lastIndexOf(':') + 1);
            if (inRange(container, port)) return true;
        }
        return false;
    }

    private static boolean inRange(String spec, int port) {
        try {
            int dash = spec.indexOf('-');
            if (dash < 0) return Integer.parseInt(spec.strip()) == port;
            return Integer.parseInt(spec.substring(0, dash).strip()) <= port
                    && port <= Integer.parseInt(spec.substring(dash + 1).strip());
        } catch (NumberFormatException e) {
            return false; // a ${VARIABLE} or something else that is not a number
        }
    }

    static List<Mount> mounts(JsonNode volumes) {
        List<Mount> mounts = new ArrayList<>();
        for (JsonNode entry : volumes) {
            if (entry.isObject()) {
                mounts.add(new Mount(entry.path("source").asText(""), entry.path("target").asText(""),
                        entry.path("read_only").asBoolean(false)));
                continue;
            }
            String text = entry.asText("");
            // A Windows source (C:\data:/app/data) has a colon of its own after the drive letter.
            int from = text.length() > 2 && Character.isLetter(text.charAt(0)) && text.charAt(1) == ':'
                    && (text.charAt(2) == '\\' || text.charAt(2) == '/') ? 2 : 0;
            int first = text.indexOf(':', from);
            if (first < 0) {
                mounts.add(new Mount("", text, false)); // an anonymous volume: only the container path
                continue;
            }
            String rest = text.substring(first + 1);
            int second = rest.indexOf(':');
            String target = second < 0 ? rest : rest.substring(0, second);
            String mode = second < 0 ? "" : rest.substring(second + 1);
            mounts.add(new Mount(text.substring(0, first), target, List.of(mode.split(",")).contains("ro")));
        }
        return mounts;
    }

    /** A service's {@code environment}, from either the mapping or the {@code KEY=value} list form. */
    static Map<String, String> environment(JsonNode environment) {
        Map<String, String> values = new LinkedHashMap<>();
        if (environment.isObject()) {
            for (Map.Entry<String, JsonNode> entry : environment.properties()) {
                values.put(entry.getKey(), entry.getValue().asText(""));
            }
        } else {
            for (JsonNode entry : environment) {
                String text = entry.asText("");
                int equals = text.indexOf('=');
                if (equals > 0) values.put(text.substring(0, equals), text.substring(equals + 1));
            }
        }
        return values;
    }

    private static boolean endsWithDockerSock(String path) {
        return path.endsWith("docker.sock");
    }

    private static String stripTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    // Best effort: only a bind source that is a path on this machine and already exists can be
    // looked at. A named volume, a ${VARIABLE} or a path that is not there yet says nothing.
    private static String fileWhereADirectoryIsNeeded(String source, Path baseDir) {
        if (!(source.startsWith("/") || source.startsWith("."))) return null;
        try {
            Path path = baseDir.resolve(source).normalize();
            return Files.isRegularFile(path) ? path.toString() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
