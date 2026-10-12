package io.floci.cli.commands.config;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.Profile;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import io.floci.cli.util.Causes;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.UnaryOperator;

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
        this(profile, store, System::getenv);
    }

    /** Test seam: {@code env} answers the {@code VISUAL}/{@code EDITOR} lookup that {@code edit} makes. */
    public ConfigProfileCommand(ProductProfile profile, ProfileStore store, UnaryOperator<String> env) {
        this.profile = profile;
        this.store = store;
        this.env = env;
        this.global = new GlobalOptions(profile);
    }

    private final UnaryOperator<String> env;

    @Parameters(index = "0", description = "Action: list, show, create, edit, delete", paramLabel = "list|show|create|edit|delete")
    String action;

    @Parameters(index = "1", description = "Profile name (required for show, create, edit, delete)", arity = "0..1", paramLabel = "<name>")
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

        try {
            return switch (action.toLowerCase()) {
                case "list" -> listProfiles(printer, store);
                case "show" -> showProfile(printer, store);
                case "create" -> createProfile(printer, store);
                case "edit" -> editProfile(printer, store);
                case "delete" -> deleteProfile(printer, store);
                default -> {
                    printer.error("Unknown action '" + action + "'.\nUse one of: list, show, create, edit, delete.");
                    yield ExitCode.USAGE;
                }
            };
        } catch (IllegalArgumentException e) {
            // An invalid profile name: the same cause exits 2 from --profile, so it does here too.
            // The message already ends with the next step.
            printer.error(e.getMessage());
            return ExitCode.USAGE;
        }
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
                    out.add(row(p));
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
            printer.error("Could not list profiles: " + Causes.firstLine(e),
                    "Check the files under ~/.floci/profiles and re-run the command.");
            return 1;
        }
    }

    // One profile as structured output: the same keys from list and show, null where unset.
    private static Map<String, Object> row(Profile p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", p.name);
        row.put("container", p.container);
        row.put("port", p.port);
        row.put("endpoint", p.endpoint);
        row.put("image", p.image);
        row.put("persistDir", p.persistDir);
        row.put("services", p.services);
        row.put("namespace", p.namespace);
        return row;
    }

    // What create, edit and delete report in structured output: the profile, what was done to it
    // and the file or files involved.
    private static Map<String, Object> done(String name, String action, List<Path> files) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("action", action);
        result.put("files", files.stream().map(Path::toString).toList());
        return result;
    }

    private int showProfile(Printer printer, ProfileStore store) {
        if (name == null) { printer.error(nameRequired()); return ExitCode.USAGE; }
        try {
            var profile = store.get(name);
            if (profile.isEmpty()) {
                printer.error("Profile '" + name + "' not found.\nRun '" + this.profile.commandPrefix() + " config profile list' to see available profiles.");
                return 1;
            }
            Profile p = profile.get();
            ProfileDefaultValueProvider.warnAboutUnknownKeys(store, name, p);
            if (printer.format() != OutputFormat.text) {
                printer.structured(row(p));
                return 0;
            }
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
            printer.error("Could not read profile: " + Causes.firstLine(e), "Fix or delete the profile file, then re-run the command.");
            return 1;
        }
    }

    /**
     * Opens the profile's file in the user's editor, then reads it back. The file is the one
     * the profile is read from ({@code .yaml} or {@code .yml}); a profile that does not exist is
     * not created here, since {@code create} is what writes the product's defaults into it.
     */
    private int editProfile(Printer printer, ProfileStore store) {
        if (name == null) { printer.error(nameRequired()); return ExitCode.USAGE; }
        String prefix = this.profile.commandPrefix() + " config profile ";
        // Asked of the file, not of its contents: a profile that no longer parses is exactly the
        // one that has to be opened to be repaired.
        Path file = store.existingFile(name);
        if (!Files.exists(file)) {
            printer.error("Profile '" + name + "' not found.\nRun '" + prefix + "create " + name + "' to create it first.");
            return 1;
        }

        List<String> editor = editorCommand(env.apply("VISUAL"), env.apply("EDITOR"));
        if (editor.isEmpty()) {
            printer.error("No editor is set, so " + file + " was not opened.\n"
                    + "Set EDITOR (for example 'export EDITOR=vi'), or edit the file yourself.");
            return 1;
        }
        List<String> command = new ArrayList<>(editor);
        command.add(file.toString());
        int exit;
        try {
            exit = editorProcess(command, printer.format() != OutputFormat.text).start().waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            printer.error("Interrupted while " + editor.get(0) + " was open.\nRe-run '" + prefix + "edit " + name + "'.");
            return 1;
        } catch (IOException e) {
            printer.error("Could not start the editor '" + String.join(" ", editor) + "': " + Causes.firstLine(e),
                    "Set EDITOR to a command on your PATH, or edit " + file + " yourself.");
            return 1;
        }
        if (exit != 0) {
            printer.error("The editor '" + String.join(" ", editor) + "' exited with code " + exit + ".\n"
                    + "Re-run '" + prefix + "edit " + name + "', or edit " + file + " yourself.");
            return 1;
        }

        // Read it back: a typo that breaks the YAML should surface now, not on the next start.
        try {
            var edited = store.get(name);
            if (edited.isEmpty()) {
                printer.error("Profile '" + name + "' is gone after the edit.\nRun '" + prefix + "create " + name + "' to create it again.");
                return 1;
            }
            ProfileDefaultValueProvider.warnAboutUnknownKeys(store, name, edited.get());
        } catch (IOException e) {
            printer.error("Profile '" + name + "' is no longer valid: " + Causes.firstLine(e) + "\n"
                    + "Re-run '" + prefix + "edit " + name + "' to fix it.");
            return 1;
        }
        if (printer.format() != OutputFormat.text) {
            printer.structured(done(name, "edited", List.of(file)));
            return 0;
        }
        printer.println("Profile '" + name + "' saved " + Ansi.gray("(" + file + ")"));
        return 0;
    }

    /**
     * How the editor is started. It shares this command's terminal, so an interactive editor
     * works. With structured output its stdout goes to stderr instead: stdout carries the one
     * result document, and anything an editor or its wrapper prints there would break it.
     */
    public static ProcessBuilder editorProcess(List<String> command, boolean structured) {
        ProcessBuilder editor = new ProcessBuilder(command).inheritIO();
        if (structured) {
            // /dev/stderr is this process's own stderr, still the terminal when only stdout is
            // captured. Where there is no such file (Windows), the editor's output is dropped.
            Path stderr = Path.of("/dev/stderr");
            editor.redirectOutput(Files.isWritable(stderr)
                    ? ProcessBuilder.Redirect.appendTo(stderr.toFile())
                    : ProcessBuilder.Redirect.DISCARD);
        }
        return editor;
    }

    /**
     * The editor to run, as a command and its arguments: {@code VISUAL} first, then
     * {@code EDITOR}, as other tools do. Empty when neither is set.
     */
    public static List<String> editorCommand(String visual, String editor) {
        String chosen = visual != null && !visual.isBlank() ? visual : editor;
        if (chosen == null || chosen.isBlank()) return List.of();
        return words(chosen);
    }

    /**
     * {@code line} split into words the way a shell would for a simple command: whitespace
     * separates, and single and double quotes group (a path with spaces). A backslash is an
     * escape only in front of a space or a quote; anywhere else it is an ordinary character, so
     * a Windows path ({@code C:\Windows\System32\notepad.exe}, {@code \\server\share\edit})
     * comes through intact. Nothing is expanded; the words go straight to the process, never
     * through a shell.
     */
    static List<String> words(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    word.append(line.charAt(++i));
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < line.length() && escapable(line.charAt(i + 1))) {
                word.append(line.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (inWord) words.add(word.toString());
        return List.copyOf(words);
    }

    private static boolean escapable(char next) {
        return Character.isWhitespace(next) || next == '"' || next == '\'';
    }

    private int createProfile(Printer printer, ProfileStore store) {
        if (name == null) { printer.error(nameRequired()); return ExitCode.USAGE; }
        try {
            if (store.get(name).isPresent()) {
                printer.error("Profile '" + name + "' already exists.\nDelete it first, or edit " + store.existingFile(name) + ".");
                return 1;
            }
            if (port != null && (port < 1 || port > 65535)) {
                printer.error("--port must be between 1 and 65535, but was " + port + ".\n"
                        + "Pick a free port, for example " + (profile.defaultPort() + 10000) + ".");
                return ExitCode.USAGE;
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
            if (printer.format() != OutputFormat.text) {
                printer.structured(done(name, "created", List.of(store.profileFile(name))));
                return 0;
            }
            printer.println(Ansi.green("Created") + " profile '" + name + "' at " + store.profileFile(name));
            printer.println(Ansi.gray("Start it with: " + profile.commandPrefix() + " start --profile " + name));
            return 0;
        } catch (IOException e) {
            printer.error("Could not create profile: " + Causes.firstLine(e), "Check that ~/.floci/profiles is writable and re-run the command.");
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
        if (name == null) { printer.error(nameRequired()); return ExitCode.USAGE; }
        try {
            List<Path> removed = store.delete(name);
            if (removed.isEmpty()) {
                printer.error("Profile '" + name + "' not found.\nRun '" + profile.commandPrefix() + " config profile list' to see available profiles.");
                return 1;
            }
            String files = removed.size() > 1
                    ? " (" + removed.get(0).getFileName() + " and " + removed.get(1).getFileName() + ")"
                    : "";
            if (printer.format() != OutputFormat.text) {
                printer.structured(done(name, "deleted", removed));
                return 0;
            }
            printer.println(Ansi.green("Deleted") + " profile '" + name + "'" + files);
            return 0;
        } catch (IOException e) {
            printer.error("Could not delete profile: " + Causes.firstLine(e), "Check that ~/.floci/profiles is writable and re-run the command.");
            return 1;
        }
    }

    private String nameRequired() {
        return "Profile name required.\nPass it after the action, for example '" + profile.commandPrefix() + " config profile " + action + " dev'.";
    }
}
