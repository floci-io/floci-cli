package io.floci.cli.config;

import io.floci.cli.output.OutputFormat;
import picocli.CommandLine;
import picocli.CommandLine.IDefaultValueProvider;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.IOException;
import java.util.Optional;

/**
 * Applies a named config profile as the default value of every option it covers.
 *
 * Registered once on the root {@code CommandLine}; picocli propagates it to the whole subcommand
 * hierarchy. Picocli consults a default-value provider only for options that were NOT matched on
 * the command line, and only after the command's own arguments have been processed — which gives
 * the precedence this CLI documents, for free:
 *
 * <pre>CLI flag &gt; profile &gt; environment variable &gt; product default</pre>
 *
 * The environment variable and product default live in the {@code GlobalOptions} field initial
 * values, which picocli keeps whenever this provider returns {@code null}.
 */
public class ProfileDefaultValueProvider implements IDefaultValueProvider {

    private final ProfileStore store;

    // Resolved once per name: the provider is called for every unmatched argument, including
    // the positionals and the parent commands, which carry no --profile of their own.
    private String resolvedName;
    private Profile resolved;

    public ProfileDefaultValueProvider() {
        this(new ProfileStore());
    }

    /** Test seam: a store pointed at a temporary profiles directory. */
    public ProfileDefaultValueProvider(ProfileStore store) {
        this.store = store;
    }

    @Override
    public String defaultValue(ArgSpec arg) {
        // Positional parameters reach the provider too; only options map to profile fields.
        if (!(arg instanceof OptionSpec option)) return null;

        CommandSpec command = arg.command();
        // Group commands, the root, and `update` carry no GlobalOptions mixin.
        OptionSpec profileOption = command.findOption("--profile");
        if (profileOption == null) return null;
        String name = profileOption.getValue();
        if (name == null) return null;

        // Defaults are applied even when -h/-V was requested, so a bad profile must not stop
        // `floci start --profile typo --help` from printing its usage.
        if (helpRequested(command)) return null;

        Profile profile = resolve(name, command.commandLine());
        for (String optionName : option.names()) {
            String value = ProfileDefaults.valueFor(profile, optionName);
            if (value != null) return value;
        }
        return null;
    }

    private static boolean helpRequested(CommandSpec command) {
        for (CommandSpec spec = command; spec != null; spec = spec.parent()) {
            for (OptionSpec option : spec.options()) {
                if ((option.usageHelp() || option.versionHelp()) && Boolean.TRUE.equals(option.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Profile resolve(String name, CommandLine commandLine) {
        if (name.equals(resolvedName)) return resolved;

        Optional<Profile> loaded;
        try {
            loaded = store.get(name);
        } catch (IllegalArgumentException e) {
            throw new ProfileNotFoundException(commandLine, e.getMessage());
        } catch (IOException e) {
            throw invalid(commandLine, name, e.getMessage());
        }
        if (loaded.isEmpty()) {
            throw new ProfileNotFoundException(commandLine,
                    "Profile '" + name + "' not found in " + store.profilesDir() + ".\n"
                            + "Run 'floci config profile list' to see available profiles, "
                            + "or 'floci config profile create " + name + "' to create it.");
        }
        Profile profile = loaded.get();
        validate(commandLine, name, profile);

        resolvedName = name;
        resolved = profile;
        return resolved;
    }

    // Picocli would otherwise reject these while converting, in a message that blames an option
    // the user never typed and never names the file the value actually came from.
    private void validate(CommandLine commandLine, String name, Profile profile) {
        if (profile.port != null && (profile.port < 1 || profile.port > 65535)) {
            throw invalid(commandLine, name, "port must be between 1 and 65535, but was " + profile.port);
        }
        if (profile.output != null) {
            try {
                OutputFormat.valueOf(profile.output.toLowerCase());
            } catch (IllegalArgumentException e) {
                throw invalid(commandLine, name, "output must be text, json or yaml, but was '" + profile.output + "'");
            }
        }
    }

    private ProfileNotFoundException invalid(CommandLine commandLine, String name, String detail) {
        return new ProfileNotFoundException(commandLine,
                "Profile '" + name + "' is invalid: " + detail + "\n"
                        + "Edit " + store.profileFile(name) + " and try again.");
    }
}
