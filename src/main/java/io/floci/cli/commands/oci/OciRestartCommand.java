package io.floci.cli.commands.oci;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

@Command(
        name = "restart",
        description = "Stop and restart the Floci OCI container",
        mixinStandardHelpOptions = true
)
public class OciRestartCommand implements Callable<Integer> {

    @Mixin
    OciGlobalOptions global;

    @Override
    public Integer call() {
        Printer printer = global.printer();

        OciStopCommand stop = new OciStopCommand();
        stop.global = global;
        stop.remove = false;
        stop.timeout = 10;
        int stopResult = stop.call();
        if (stopResult != 0) return stopResult;

        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        OciStartCommand start = new OciStartCommand();
        start.global = global;
        start.port = 4599;
        start.pull = "missing";
        start.image = "floci/floci-oci:latest";
        start.detach = false;
        return start.call();
    }
}