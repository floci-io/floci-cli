package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.ProfileNotFoundException;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

@Command(
        name = "restart",
        description = "Stop and restart the Floci AWS container",
        mixinStandardHelpOptions = true
)
public class RestartCommand implements Callable<Integer> {

    protected final ProductProfile profile;

    @Mixin
    protected GlobalOptions global;

    @Spec
    CommandSpec spec;

    public RestartCommand() {
        this(ProductProfile.AWS);
    }

    protected RestartCommand(ProductProfile profile) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();

        // Resolve the profile BEFORE stopping anything. A failure here must not leave the
        // container stopped, and the values must not be re-read from a file that could change
        // during the stop.
        StartCommand start;
        try {
            start = buildStartCommand();
        } catch (ProfileNotFoundException e) {
            // Gone or broken since the parse resolved it: nothing has been stopped yet, and the
            // exit code matches a bad --profile at parse time.
            printer.error(e.getMessage());
            return ExitCode.USAGE;
        }
        String invalid = start.validationError();
        if (invalid != null) {
            printer.error(invalid);
            return 2;
        }
        String unwritable = start.preparePersistDir();
        if (unwritable != null) {
            printer.error(unwritable);
            return 1;
        }

        int stopResult = buildStopCommand().call();
        if (stopResult != 0) return stopResult;

        return start.call();
    }

    /**
     * The {@code stop} this restart runs. It removes the container: {@code docker stop} returns
     * once the container has exited, and removing it releases its port bindings, so the start
     * that follows needs neither a pause nor a second inspect-and-remove of its own.
     */
    public StopCommand buildStopCommand() {
        StopCommand stop = new StopCommand(profile);
        stop.global = global;
        stop.remove = true;
        stop.timeout = 10;
        return stop;
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
        // The outer parse's provider: its memoized profile is the snapshot the globals below were
        // resolved from, so the start settings cannot come from a newer version of the file.
        StartCommand start = StartCommand.resolvedFor(profile, ProfileDefaultValueProvider.of(spec), global.profile);

        // The real invocation's globals win: they already carry the profile plus any flag the
        // user passed, resolved once by the outer parse.
        start.global = global;
        start.pull = "missing";
        start.detach = false;
        return start;
    }
}
