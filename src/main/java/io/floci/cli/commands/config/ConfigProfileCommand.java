package io.floci.cli.commands.config;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.Profile;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "profile",
        description = "Manage Floci AWS configuration profiles",
        mixinStandardHelpOptions = true,
        subcommands = {HelpCommand.class}
)
public class ConfigProfileCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    public ConfigProfileCommand() {
        this(ProductProfile.AWS);
    }

    protected ConfigProfileCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Parameters(index = "0", description = "Action: list, show, create, delete", paramLabel = "list|show|create|delete")
    String action;

    @Parameters(index = "1", description = "Profile name (required for show, create, delete)", arity = "0..1", paramLabel = "<name>")
    String name;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        ProfileStore store = new ProfileStore();

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
            List<Profile> profiles = store.list();
            if (profiles.isEmpty()) {
                printer.println(Ansi.gray("No profiles found. Create one with: " + profile.commandPrefix() + " config profile create <name>"));
                return 0;
            }
            printer.println(Ansi.bold("Profiles:"));
            for (Profile p : profiles) {
                printer.println("  " + p.name + Ansi.gray("  " + (p.endpoint != null ? p.endpoint : "")));
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
            Profile p = new Profile(name);
            store.save(p);
            printer.println(Ansi.green("Created") + " profile '" + name + "' at " + store.profileFile(name));
            printer.println(Ansi.gray("Edit the file to customize endpoint, container, image, etc."));
            return 0;
        } catch (IOException e) {
            printer.error("Could not create profile: " + e.getMessage());
            return 1;
        }
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
