package io.floci.cli.config;

import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

/**
 * Raised while resolving {@code --profile <name>}. Extends {@link ParameterException} so picocli
 * propagates it unwrapped from the default-value provider; {@code FlociCli} recognises it and
 * prints the message alone (no usage dump) with exit code 2.
 */
public class ProfileNotFoundException extends ParameterException {

    public ProfileNotFoundException(CommandLine commandLine, String message) {
        super(commandLine, message);
    }
}
