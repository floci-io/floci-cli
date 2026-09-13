package io.floci.cli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class ProfileStore {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    // A profile name becomes a file name, so it must not be able to traverse out of the
    // profiles directory: 'floci config profile delete ../../foo' resolved the raw name.
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9._-]+");

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
        if (".".equals(name) || "..".equals(name) || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid profile name '" + name + "'. Use only letters, digits, '.', '_' and '-'.\n"
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
        return profilesDir.resolve(validateName(name) + ".yaml");
    }

    // list() has always accepted .yml, so reads must too — otherwise a .yml profile shows up in
    // 'config profile list' and then reports "not found" when passed to --profile.
    private Path existingProfileFile(String name) {
        Path yaml = profileFile(name);
        if (Files.exists(yaml)) return yaml;
        Path yml = profilesDir.resolve(validateName(name) + ".yml");
        return Files.exists(yml) ? yml : yaml;
    }

    /** The directory profiles are read from, for user-facing messages. */
    public Path profilesDir() {
        return profilesDir;
    }
}
