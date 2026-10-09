package io.floci.cli.azcli;

import java.util.List;
import java.util.Map;

/**
 * Runs the {@code az} CLI. An interface so 'floci az setup' can be tested without {@code az}
 * installed: tests pass a fake that records the calls.
 */
@FunctionalInterface
public interface AzCli {

    /**
     * Runs {@code az <args>} with {@code env} applied over the inherited environment; a
     * {@code null} value removes that variable.
     *
     * @throws AzCliNotFoundException when no {@code az} executable is on the PATH
     */
    Result run(List<String> args, Map<String, String> env) throws AzCliNotFoundException;

    /** Exit code plus stdout and stderr, interleaved. */
    record Result(int exit, String output) {
        public boolean ok() {
            return exit == 0;
        }
    }
}
