package io.floci.cli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.floci.cli.output.Ansi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class ProfileStore {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    // A profile name becomes a file name, so it must not be able to traverse out of the
    // profiles directory: 'floci config profile delete ../../foo' resolved the raw name.
    //
    // This is a deny-list on purpose. An allow-list over the whole character set also rejects
    // names earlier versions accepted (a space, '+', '@'), which left those profiles listed by
    // 'config profile list' and unreachable by show, --profile and delete, with no way to remove
    // them through the CLI. The guarantee is carried by resolveInProfilesDir below, not by the
    // character rules.
    //
    // Backslash is deliberately NOT here. Java NIO treats it as a separator on Windows, where
    // resolveInProfilesDir already rejects '..\\..\\escape' on the parent check, and as an
    // ordinary file-name character on Unix, where 0.2.1 could create 'team\\alpha.yaml' and
    // list() still returns it. Denying it would orphan that profile on Unix for no gain.
    private static final Pattern PATH_SEPARATOR = Pattern.compile("/");

    private final Path profilesDir;

    public ProfileStore() {
        this(Path.of(System.getProperty("user.home"), ".floci", "profiles"));
    }

    public ProfileStore(Path profilesDir) {
        this.profilesDir = profilesDir;
    }

    /**
     * Rejects anything that could escape {@link #profilesDir} or name a hidden path segment.
     * @throws IllegalArgumentException with a message suitable for printing to the user
     */
    public static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Profile name must not be empty.\n"
                            + "Run 'floci config profile list' to see available profiles.");
        }
        if (".".equals(name) || "..".equals(name) || PATH_SEPARATOR.matcher(name).find()) {
            throw new IllegalArgumentException(
                    "Invalid profile name '" + name + "'. It must not be '.', '..', or contain '/'.\n"
                            + "Run 'floci config profile list' to see available profiles.");
        }
        return name;
    }

    public List<Profile> list() throws IOException {
        if (!Files.exists(profilesDir)) return List.of();
        List<Profile> profiles = new ArrayList<>();
        try (var stream = Files.list(profilesDir)) {
            stream.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    // A file no command can address (".yaml" has an empty name) is not a profile.
                    .filter(p -> isUsableName(nameOf(p)))
                    .forEach(p -> {
                        try {
                            profiles.add(read(p));
                        } catch (IOException e) {
                            // Skipped, but not silently: --profile on the same name would fail.
                            // The cause is quoted, not guessed: this is a parse error or a
                            // file that could not be opened.
                            System.err.println(Ansi.yellow("Warning: ") + "Could not read profile file " + p
                                    + ": " + firstLine(e.getMessage())
                                    + "\nCheck the file and its permissions, or delete it.");
                        }
                    });
        }
        return profiles;
    }

    public Optional<Profile> get(String name) throws IOException {
        Path file = existingFile(name);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(read(file));
    }

    private static String firstLine(String message) {
        if (message == null) return "unreadable";
        int newline = message.indexOf('\n');
        return newline >= 0 ? message.substring(0, newline) : message;
    }

    // The one read path, so list and show agree on a profile's name.
    private static Profile read(Path file) throws IOException {
        Profile profile = YAML.readValue(file.toFile(), Profile.class);
        // Hand-written files often omit 'name:'; the file name is the name.
        if (profile.name == null || profile.name.isBlank()) {
            profile.name = nameOf(file);
        }
        return profile;
    }

    private static boolean isUsableName(String name) {
        try {
            validateName(name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String nameOf(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.substring(0, fileName.lastIndexOf('.'));
    }

    public void save(Profile profile) throws IOException {
        AtomicFiles.write(profileFile(profile.name), YAML.writeValueAsBytes(profile));
    }

    /**
     * Deletes every spelling of {@code name}. Removing only the one reads prefer would leave the
     * other answering {@code --profile} after a delete that reported success.
     *
     * @return the files removed: 0 when the profile did not exist
     */
    public List<Path> delete(String name) throws IOException {
        List<Path> removed = new ArrayList<>();
        for (Path file : List.of(profileFile(name), resolveInProfilesDir(validateName(name), ".yml"))) {
            if (Files.deleteIfExists(file)) removed.add(file);
        }
        return removed;
    }

    /** Where {@link #save} writes {@code name}. Always {@code .yaml}. */
    public Path profileFile(String name) {
        return resolveInProfilesDir(validateName(name), ".yaml");
    }

    // The traversal guarantee: whatever the name looks like, the file it resolves to must sit
    // directly in profilesDir. Also the place a name that is illegal on this platform but legal
    // on another (a colon on Windows) turns into a clean message instead of InvalidPathException.
    // Messages quote the name as the user typed it, not the file name with its suffix.
    private Path resolveInProfilesDir(String name, String suffix) {
        Path dir = profilesDir.toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = dir.resolve(name + suffix).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(
                    "Invalid profile name '" + name + "': not a valid file name on this platform.\n"
                            + "Run 'floci config profile list' to see available profiles.");
        }
        if (!dir.equals(resolved.getParent())) {
            throw new IllegalArgumentException(
                    "Invalid profile name '" + name + "': it would resolve outside " + profilesDir + ".\n"
                            + "Run 'floci config profile list' to see available profiles.");
        }
        return resolved;
    }

    /**
     * The file {@code name} is read from: its {@code .yaml}, else its {@code .yml}, else the
     * {@code .yaml} path {@link #save} would create. Messages that tell the user which file to
     * edit name this one, not {@link #profileFile}.
     *
     * <p>list() has always accepted .yml, so reads must too — otherwise a .yml profile shows up in
     * 'config profile list' and then reports "not found" when passed to --profile.
     */
    public Path existingFile(String name) {
        Path yaml = profileFile(name);
        if (Files.exists(yaml)) return yaml;
        Path yml = resolveInProfilesDir(validateName(name), ".yml");
        return Files.exists(yml) ? yml : yaml;
    }

    /** The directory profiles are read from, for user-facing messages. */
    public Path profilesDir() {
        return profilesDir;
    }
}
