package io.floci.cli.commands.config;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.Profile;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.io.IOException;
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
        startSettings().ifPresent(p -> {
            if (p.image != null) data.put("image", p.image);
            if (p.port != null) data.put("port", p.port);
            if (p.persistDir != null) data.put("persistDir", p.persistDir);
            if (p.services != null) data.put("services", p.services);
        });

        if (printer.format() != OutputFormat.text) {
            printer.structured(data);
            return 0;
        }

        printer.println(Ansi.bold("Active Configuration (" + profile.displayName() + ")"));
        printer.println("");
        data.forEach((key, value) -> printer.println(String.format("  %-12s%s", label(key), value)));

        return 0;
    }

    private Optional<Profile> startSettings() {
        if (global.profile == null) return Optional.empty();
        try {
            return store.get(global.profile);
        } catch (IOException | IllegalArgumentException e) {
            // Parsing already resolved this name; nothing useful to add here.
            return Optional.empty();
        }
    }

    private static String label(String key) {
        return Character.toUpperCase(key.charAt(0)) + key.substring(1) + ":";
    }
}
