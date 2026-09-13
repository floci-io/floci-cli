package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.Profile;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "restart",
        description = "Stop and restart the Floci AWS container",
        mixinStandardHelpOptions = true
)
public class RestartCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    private final ProfileStore store;

    @Mixin
    protected GlobalOptions global;

    public RestartCommand() {
        this(ProductProfile.AWS);
    }

    protected RestartCommand(ProductProfile profile) {
        this(profile, new ProfileStore());
    }

    /** Test seam: a store pointed at a temporary profiles directory. */
    public RestartCommand(ProductProfile profile, ProfileStore store) {
        this.profile = profile;
        this.store = store;
        this.global = new GlobalOptions(profile);
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();

        StopCommand stop = new StopCommand(profile);
        stop.global = global;
        stop.remove = false;
        stop.timeout = 10;
        int stopResult = stop.call();
        if (stopResult != 0) return stopResult;

        // Minimal wait to avoid port-already-in-use races
        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        return buildStartCommand().call();
    }

    /**
     * The {@code start} this restart will run. Picocli never parses this instance, so the profile
     * that {@code ProfileDefaultValueProvider} applied to the parsed command line has to be
     * carried across by hand — without it, {@code restart --profile x} would silently drop the
     * profile's persistence directory and service list.
     */
    public StartCommand buildStartCommand() {
        StartCommand start = new StartCommand(profile);
        start.global = global;
        start.pull = "missing";
        start.detach = false;

        // port and image already hold the product defaults, set by the StartCommand constructor.
        if (global.profile != null) {
            loadProfile().ifPresent(p -> {
                if (p.port != null) start.port = p.port;
                if (p.image != null) start.image = p.image;
                if (p.persistDir != null) start.persistDir = p.persistDir;
                if (p.services != null) start.services = p.services;
            });
        }
        return start;
    }

    private Optional<Profile> loadProfile() {
        try {
            return store.get(global.profile);
        } catch (IOException | IllegalArgumentException e) {
            // Parsing already resolved this name, so a failure here cannot happen in practice;
            // keeping the product defaults beats aborting a restart that has already stopped.
            return Optional.empty();
        }
    }
}
