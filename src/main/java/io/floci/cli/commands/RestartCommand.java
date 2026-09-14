package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

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

        // Resolve the profile BEFORE stopping anything. A failure here must not leave the
        // container stopped, and the values must not be re-read from a file that could change
        // during the stop plus the one second wait below.
        StartCommand start = buildStartCommand();

        StopCommand stop = new StopCommand(profile);
        stop.global = global;
        stop.remove = false;
        stop.timeout = 10;
        int stopResult = stop.call();
        if (stopResult != 0) return stopResult;

        // Minimal wait to avoid port-already-in-use races
        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        return start.call();
    }

    /**
     * The {@code start} this restart will run. Picocli never parses this instance, so the profile
     * has to be applied by hand, and copying fields out of the Profile bean is not good enough:
     * values reach a real {@code start} through picocli, which interpolates {@code ${env:HOME}}
     * and friends. Going through {@link StartCommand#resolvedFor} keeps the two paths on the same
     * provider, the same precedence and the same interpolation, so one profile cannot mean one
     * directory on {@code start} and a different one on {@code restart}.
     */
    public StartCommand buildStartCommand() {
        StartCommand start = StartCommand.resolvedFor(profile, store, global.profile);

        // The real invocation's globals win: they already carry the profile plus any flag the
        // user passed, resolved once by the outer parse.
        start.global = global;
        start.pull = "missing";
        start.detach = false;
        return start;
    }
}
