package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
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

    @Mixin
    protected GlobalOptions global;

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

        StopCommand stop = new StopCommand(profile);
        stop.global = global;
        stop.remove = false;
        stop.timeout = 10;
        int stopResult = stop.call();
        if (stopResult != 0) return stopResult;

        // Minimal wait to avoid port-already-in-use races
        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        StartCommand start = new StartCommand(profile);
        start.global = global;
        start.port = profile.defaultPort();
        start.pull = "missing";
        start.image = profile.defaultImageRef();
        start.detach = false;
        return start.call();
    }
}
