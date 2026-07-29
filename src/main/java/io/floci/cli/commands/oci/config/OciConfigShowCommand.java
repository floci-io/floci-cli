package io.floci.cli.commands.oci.config;

import io.floci.cli.OciGlobalOptions;
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
        description = "Show the active Floci OCI configuration",
        mixinStandardHelpOptions = true
)
public class OciConfigShowCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        ProfileStore store = new ProfileStore();

        Profile active = new Profile(global.profile != null ? global.profile : "default");
        active.endpoint = global.endpoint;
        active.container = global.container;
        active.output = global.output != null ? global.output.name() : "text";

        if (global.profile != null) {
            try {
                Optional<Profile> loaded = store.get(global.profile);
                if (loaded.isPresent()) {
                    active = loaded.get();
                } else {
                    printer.warn("Profile '" + global.profile + "' not found. Using defaults.");
                }
            } catch (IOException e) {
                printer.warn("Could not load profile: " + e.getMessage());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profile", active.name);
        data.put("endpoint", active.endpoint != null ? active.endpoint : global.endpoint);
        data.put("container", active.container != null ? active.container : global.container);
        data.put("output", active.output);

        if (printer.format() != OutputFormat.text) {
            printer.structured(data);
            return 0;
        }

        printer.println(Ansi.bold("Active Configuration (Floci OCI)"));
        printer.println("");
        printer.println("  Profile:    " + active.name);
        printer.println("  Endpoint:   " + (active.endpoint != null ? active.endpoint : global.endpoint));
        printer.println("  Container:  " + (active.container != null ? active.container : global.container));
        printer.println("  Output:     " + (active.output != null ? active.output : "text"));

        return 0;
    }
}
