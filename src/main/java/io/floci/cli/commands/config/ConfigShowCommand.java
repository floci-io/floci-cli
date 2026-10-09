package io.floci.cli.commands.config;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StartCommand;
import io.floci.cli.config.Profile;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileNotFoundException;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "show",
        description = "Show the active Floci AWS configuration",
        mixinStandardHelpOptions = true
)
public class ConfigShowCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    @Spec
    CommandSpec spec;

    public ConfigShowCommand() {
        this(ProductProfile.AWS);
    }

    protected ConfigShowCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();

        // global.* already reflects the profile: ProfileDefaultValueProvider applied it during
        // parsing, and only to options the user did not pass, so explicit flags still win here.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", global.profile != null ? global.profile : "default");
        data.put("endpoint", global.endpoint);
        data.put("container", global.container);
        data.put("output", global.output != null ? global.output.name() : "text");

        // image/port/persistDir/services have no global option, so they are read back from the
        // profile itself — they only take effect on 'start'.
        try {
            addStartSettings(data);
        } catch (ProfileNotFoundException e) {
            // The profile went away (or broke) after the parse resolved it. Same cause, same exit
            // code as a bad --profile at parse time.
            printer.error(e.getMessage());
            return ExitCode.USAGE;
        }

        if (printer.format() != OutputFormat.text) {
            printer.structured(data);
            return 0;
        }

        printer.println(Ansi.bold("Active Configuration (" + profile.displayName() + ")"));
        printer.println("");
        data.forEach((key, value) -> printer.println(String.format("  %-12s%s", label(key), value)));

        return 0;
    }

    // Values come from a profile-resolved StartCommand rather than the Profile bean, so an
    // interpolated persistDir reads here exactly as 'start' would use it. The bean is consulted
    // for presence, so a product default never shows up looking like a profile value.
    //
    // All of it comes from ONE snapshot, the one the outer parse took for the global rows above:
    // the provider is that parse's, and it memoizes the profile it read, so no row can come from a
    // newer version of the file. Built by hand, outside FlociCli's wiring, the provider is fresh
    // and reads the file here; if it has gone, resolvedFor throws and call() exits 2.
    private void addStartSettings(Map<String, Object> data) {
        if (global.profile == null) return;

        ProfileDefaultValueProvider provider = ProfileDefaultValueProvider.of(spec);
        StartCommand resolved = StartCommand.resolvedFor(profile, provider, global.profile);
        Optional<Profile> declared = provider.resolved();
        if (declared.isEmpty()) return;

        Profile p = declared.get();
        if (p.image != null) data.put("image", resolved.image());
        if (p.port != null) data.put("port", resolved.port());
        if (p.persistDir != null) data.put("persistDir", resolved.persistDir());
        if (p.services != null) data.put("services", resolved.services());
        // Effective, not declared: a profile with its own container gets a namespace unasked, and
        // the container in effect (a --container flag beats the profile) is the one it comes from.
        String namespace = resolved.resourceNamespaceFor(global.container);
        if (namespace != null) data.put("namespace", namespace);
    }

    private static String label(String key) {
        return Character.toUpperCase(key.charAt(0)) + key.substring(1) + ":";
    }
}
