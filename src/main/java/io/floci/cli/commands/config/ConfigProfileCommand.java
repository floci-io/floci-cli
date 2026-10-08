package io.floci.cli.commands.config;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.Profile;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "profile",
        description = "Manage Floci AWS configuration profiles",
        mixinStandardHelpOptions = true,
        subcommands = {HelpCommand.class}
)
public class ConfigProfileCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    private final ProfileStore store;

    @Mixin
    protected GlobalOptions global;

    public ConfigProfileCommand() {
        this(ProductProfile.AWS);
    }

    protected ConfigProfileCommand(ProductProfile profile) {
        this(profile, new ProfileStore());
    }

    /** Test seam: a store pointed at a temporary profiles directory. */
    public ConfigProfileCommand(ProductProfile profile, ProfileStore store) {
        this.profile = profile;
        this.store = store;
        this.global = new GlobalOptions(profile);
    }

    @Parameters(index = "0", description = "Action: list, show, create, delete", paramLabel = "list|show|create|delete")
    String action;

    @Parameters(index = "1", description = "Profile name (required for show, create, delete)", arity = "0..1", paramLabel = "<name>")
    String name;

    // create only. With --container they describe one instance, so several profiles can run the
    // same emulator side by side; unset ones keep the defaults of the tree create runs under.
    @Option(names = {"--port"}, description = "create: host port for this instance", paramLabel = "<port>")
    Integer port;

    @Option(names = {"--image"}, description = "create: Docker image for this instance", paramLabel = "<image>")
    String image;

    @Option(names = {"--persist"}, description = "create: host directory that keeps this instance's state", paramLabel = "<dir>")
    String persistDir;

    @Option(names = {"--services"}, description = "create: comma-separated services to enable", paramLabel = "<csv>")
    String services;

    @Option(names = {"--namespace"}, description = "create: Docker resource namespace for the containers this instance launches (default: derived from --container)", paramLabel = "<name>")
    String namespace;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        Printer printer = global.printer();

        return switch (action.toLowerCase()) {
            case "list" -> listProfiles(printer, store);
            case "show" -> showProfile(printer, store);
            case "create" -> createProfile(printer, store);
            case "delete" -> deleteProfile(printer, store);
            default -> {
                printer.error("Unknown action '" + action + "'. Use: list, show, create, delete");
                yield 1;
            }
        };
    }

    private int listProfiles(Printer printer, ProfileStore store) {
        try {
            List<Profile> profiles = store.list().stream()
                    .sorted(Comparator.comparing(p -> String.valueOf(p.name)))
                    .toList();
            // Structured output first: an empty store is still a valid, parseable empty list.
            if (printer.format() != OutputFormat.text) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Profile p : profiles) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", p.name);
                    row.put("container", p.container);
                    row.put("port", p.port);
                    row.put("endpoint", p.endpoint);
                    row.put("image", p.image);
                    row.put("persistDir", p.persistDir);
                    row.put("services", p.services);
                    row.put("namespace", p.namespace);
                    out.add(row);
                }
                printer.structured(out);
                return 0;
            }
            if (profiles.isEmpty()) {
                printer.println(Ansi.gray("No profiles found. Create one with: " + profile.commandPrefix() + " config profile create <name>"));
                return 0;
            }
            // One row per instance: the container and port are what tell two instances apart.
            printer.println(Ansi.bold("Profiles:"));
            int width = profiles.stream().mapToInt(p -> String.valueOf(p.name).length()).max().orElse(0);
            for (Profile p : profiles) {
                StringBuilder row = new StringBuilder("  ").append(pad(String.valueOf(p.name), width));
                if (p.container != null) row.append("  ").append(p.container);
                if (p.port != null) row.append(Ansi.gray("  :" + p.port));
                if (p.persistDir != null) row.append(Ansi.gray("  " + p.persistDir));
                printer.println(row.toString());
            }
            return 0;
        } catch (IOException e) {
            printer.error("Could not list profiles: " + e.getMessage());
            return 1;
        }
    }

    private int showProfile(Printer printer, ProfileStore store) {
        if (name == null) { printer.error("Profile name required."); return 1; }
        try {
            var profile = store.get(name);
            if (profile.isEmpty()) { printer.error("Profile '" + name + "' not found."); return 1; }
            Profile p = profile.get();
            printer.println(Ansi.bold("Profile: ") + p.name);
            if (p.endpoint != null)   printer.println("  endpoint:   " + p.endpoint);
            if (p.container != null)  printer.println("  container:  " + p.container);
            if (p.image != null)      printer.println("  image:      " + p.image);
            if (p.port != null)       printer.println("  port:       " + p.port);
            if (p.persistDir != null) printer.println("  persistDir: " + p.persistDir);
            if (p.services != null)   printer.println("  services:   " + p.services);
            if (p.namespace != null)  printer.println("  namespace:  " + p.namespace);
            return 0;
        } catch (IOException e) {
            printer.error("Could not read profile: " + e.getMessage());
            return 1;
        }
    }

    private int createProfile(Printer printer, ProfileStore store) {
        if (name == null) { printer.error("Profile name required."); return 1; }
        try {
            if (store.get(name).isPresent()) {
                printer.error("Profile '" + name + "' already exists. Delete it first or edit " + store.profileFile(name));
                return 1;
            }
            if (port != null && (port < 1 || port > 65535)) {
                printer.error("--port must be between 1 and 65535, but was " + port + ".\n"
                        + "Pick a free port, for example " + (profile.defaultPort() + 10000) + ".");
                return 1;
            }
            Profile p = new Profile(profile, name);
            // Only flags the user typed: the mixin's own values may come from FLOCI_* env vars, and
            // a provider-applied --profile, neither of which should be baked into a new file.
            if (typed("--container")) p.container = global.container;
            if (typed("--endpoint")) p.endpoint = global.endpoint;
            if (typed("--port")) p.port = port;
            if (typed("--image")) p.image = image;
            if (typed("--persist")) p.persistDir = persistDir;
            if (typed("--services")) p.services = services;
            if (typed("--namespace")) p.namespace = namespace;

            for (String warning : collisions(profile, store.list(), p)) {
                printer.warn(warning);
            }
            store.save(p);
            printer.println(Ansi.green("Created") + " profile '" + name + "' at " + store.profileFile(name));
            printer.println(Ansi.gray("Start it with: " + profile.commandPrefix() + " start --profile " + name));
            return 0;
        } catch (IOException e) {
            printer.error("Could not create profile: " + e.getMessage());
            return 1;
        }
    }

    private boolean typed(String option) {
        return spec.commandLine().getParseResult().hasMatchedOption(option);
    }

    /**
     * Two profiles that share a container are the same instance, and two that share a port on the
     * same image cannot run at once. Two of one emulator that share a resource namespace remove
     * each other's child containers. All are legitimate (one instance, used at different times),
     * so they are warnings, not errors.
     */
    static List<String> collisions(ProductProfile product, List<Profile> existing, Profile created) {
        List<String> warnings = new ArrayList<>();
        for (Profile other : existing) {
            if (other.name == null || other.name.equals(created.name)) continue;
            if (created.container != null && created.container.equals(other.container)) {
                warnings.add("Profile '" + other.name + "' already uses container '" + created.container + "'.\n"
                        + "Pick another --container, or start only one of them at a time.");
                continue; // one instance: its port and namespace are the same by definition
            }
            boolean sameEmulator = repository(created.image).equals(repository(other.image));
            if (sameEmulator && created.port != null && created.port.equals(other.port)) {
                warnings.add("Profile '" + other.name + "' already uses port " + created.port + ".\n"
                        + "Pick another --port, or start only one of them at a time.");
            }
            // Reported separately: a new port alone does not stop shared child containers.
            String ns = sameEmulator ? namespaceOf(product, created) : null;
            if (ns != null && ns.equals(namespaceOf(product, other))) {
                warnings.add("Profile '" + other.name + "' already uses resource namespace '" + ns + "'.\n"
                        + "Pass --namespace to give this instance its own.");
            }
        }
        return warnings;
    }

    private static String namespaceOf(ProductProfile product, Profile p) {
        return p.namespace != null && !p.namespace.isBlank() ? p.namespace : product.resourceNamespace(p.container);
    }

    // floci/floci-az:latest -> floci/floci-az, so two tags of one emulator still collide on a port.
    private static String repository(String image) {
        if (image == null) return "";
        // A digest (repo@sha256:...) carries a colon of its own; drop it before looking for a tag.
        int digest = image.indexOf('@');
        if (digest >= 0) image = image.substring(0, digest);
        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        return colon > slash ? image.substring(0, colon) : image;
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    private int deleteProfile(Printer printer, ProfileStore store) {
        if (name == null) { printer.error("Profile name required."); return 1; }
        try {
            if (!store.delete(name)) {
                printer.error("Profile '" + name + "' not found.");
                return 1;
            }
            printer.println(Ansi.green("Deleted") + " profile '" + name + "'");
            return 0;
        } catch (IOException e) {
            printer.error("Could not delete profile: " + e.getMessage());
            return 1;
        }
    }
}
