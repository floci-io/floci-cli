package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileNotFoundException;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
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
        this(profile, new DockerClient());
    }

    /** Test seam: {@code docker} answers the inspect that recovers the running instance's settings. */
    public RestartCommand(ProductProfile profile, DockerClient docker) {
        this.profile = profile;
        this.global = new GlobalOptions(profile);
        this.docker = docker;
    }

    private final DockerClient docker;

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
        } catch (DockerException e) {
            // The container may well be there with settings this restart could not read. Going
            // on would remove it and bring it back on the defaults, so stop here instead.
            printer.error("Could not read how container '" + global.container + "' was started, so it was left as it is: "
                    + e.getMessage()
                    + "\nCheck that Docker is responsive ('docker info'), then re-run '"
                    + profile.commandPrefix() + " restart" + global.instanceSelector() + "'.");
            return 1;
        }
        String invalid = start.validationError();
        if (invalid != null) {
            printer.error(invalid);
            return 2;
        }

        if (!containerExists()) {
            // Nothing to stop: start it, rather than failing on the stop.
            printer.println("Container '" + global.container + "' not found; starting it.");
            return start.call();
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
    public StartCommand buildStartCommand() throws DockerException {
        // The outer parse's provider: its memoized profile is the snapshot the globals below were
        // resolved from, so the start settings cannot come from a newer version of the file.
        StartCommand start = StartCommand.resolvedFor(profile, ProfileDefaultValueProvider.of(spec), global.profile);

        // The real invocation's globals win: they already carry the profile plus any flag the
        // user passed, resolved once by the outer parse.
        start.global = global;
        start.pull = "missing";
        start.detach = false;
        if (global.profile == null) carryOver(start);
        return start;
    }

    // Without a profile, the running container is the only record of how it was started: its
    // port, image, data directory and services come back from it, or a restart would quietly
    // move the instance to the default port and drop its state. Read before the stop, which
    // removes the container. Only a container that does not exist (an empty result) means
    // "start with the defaults"; a failed read is thrown, so the caller can stop before the stop.
    private void carryOver(StartCommand start) throws DockerException {
        docker.inspectRunSettings(global.container, profile.defaultPort()).ifPresent(run -> {
            if (run.hostPort() != null) start.port = run.hostPort();
            if (run.image() != null) start.image = run.image();
            if (run.persistSource() != null) start.persistDir = run.persistSource();
            String services = run.env().get(profile.envVar("SERVICES"));
            if (services != null) start.services = services;
            String namespace = run.env().get(profile.envVar("DOCKER_RESOURCE_NAMESPACE"));
            if (namespace != null) start.namespace = namespace;
        });
    }

    private boolean containerExists() {
        try {
            return docker.inspectContainer(global.container).isPresent();
        } catch (DockerException e) {
            return true; // let stop report the docker problem
        }
    }
}
