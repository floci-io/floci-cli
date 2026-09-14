package io.floci.cli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

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
                    .forEach(p -> {
                        try {
                            Profile profile = YAML.readValue(p.toFile(), Profile.class);
                            // Hand-written files often omit 'name:'; the file name is the name.
                            if (profile.name == null || profile.name.isBlank()) {
                                String file = p.getFileName().toString();
                                profile.name = file.substring(0, file.lastIndexOf('.'));
                            }
                            profiles.add(profile);
                        } catch (IOException ignored) {}
                    });
        }
        return profiles;
    }

    public Optional<Profile> get(String name) throws IOException {
        Path file = existingProfileFile(name);
        if (!Files.exists(file)) return Optional.empty();
        return Optional.of(YAML.readValue(file.toFile(), Profile.class));
    }

    public void save(Profile profile) throws IOException {
        Files.createDirectories(profilesDir);
        YAML.writeValue(profileFile(profile.name).toFile(), profile);
    }

    public boolean delete(String name) throws IOException {
        Path file = existingProfileFile(name);
        return Files.deleteIfExists(file);
    }

    /** Where {@link #save} writes {@code name}. Always {@code .yaml}. */
    public Path profileFile(String name) {
        return resolveInProfilesDir(validateName(name) + ".yaml");
    }

    // The traversal guarantee: whatever the name looks like, the file it resolves to must sit
    // directly in profilesDir. Also the place a name that is illegal on this platform but legal
    // on another (a colon on Windows) turns into a clean message instead of InvalidPathException.
    private Path resolveInProfilesDir(String fileName) {
        Path dir = profilesDir.toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = dir.resolve(fileName).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(
                    "Invalid profile name '" + fileName + "': not a valid file name on this platform.\n"
                            + "Run 'floci config profile list' to see available profiles.");
        }
        if (!dir.equals(resolved.getParent())) {
            throw new IllegalArgumentException(
                    "Invalid profile name '" + fileName + "': it would resolve outside " + profilesDir + ".\n"
                            + "Run 'floci config profile list' to see available profiles.");
        }
        return resolved;
    }

    // list() has always accepted .yml, so reads must too — otherwise a .yml profile shows up in
    // 'config profile list' and then reports "not found" when passed to --profile.
    private Path existingProfileFile(String name) {
        Path yaml = profileFile(name);
        if (Files.exists(yaml)) return yaml;
        Path yml = resolveInProfilesDir(validateName(name) + ".yml");
        return Files.exists(yml) ? yml : yaml;
    }

    /** The directory profiles are read from, for user-facing messages. */
    public Path profilesDir() {
        return profilesDir;
    }
}
