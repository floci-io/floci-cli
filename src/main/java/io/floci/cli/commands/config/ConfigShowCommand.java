package io.floci.cli.commands.config;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.commands.StartCommand;
import io.floci.cli.config.Profile;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

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

    private final ProfileStore store;

    @Mixin
    protected GlobalOptions global;

    public ConfigShowCommand() {
        this(ProductProfile.AWS);
    }

    protected ConfigShowCommand(ProductProfile profile) {
        this(profile, new ProfileStore());
    }

    /** Test seam: a store pointed at a temporary profiles directory. */
    public ConfigShowCommand(ProductProfile profile, ProfileStore store) {
        this.profile = profile;
        this.store = store;
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
        addStartSettings(data);

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
    // Both come from ONE snapshot: the provider memoizes the profile it read, so presence and
    // values cannot disagree if another process edits the file mid-command. If the profile has
    // gone since the outer parse resolved it, resolvedFor throws and the command fails loudly,
    // which beats printing half of an old profile next to half of a new one.
    private void addStartSettings(Map<String, Object> data) {
        if (global.profile == null) return;

        ProfileDefaultValueProvider provider = new ProfileDefaultValueProvider(store);
        StartCommand resolved = StartCommand.resolvedFor(profile, provider, global.profile);
        Optional<Profile> declared = provider.resolved();
        if (declared.isEmpty()) return;

        Profile p = declared.get();
        if (p.image != null) data.put("image", resolved.image());
        if (p.port != null) data.put("port", resolved.port());
        if (p.persistDir != null) data.put("persistDir", resolved.persistDir());
        if (p.services != null) data.put("services", resolved.services());
    }

    private static String label(String key) {
        return Character.toUpperCase(key.charAt(0)) + key.substring(1) + ":";
    }
}
