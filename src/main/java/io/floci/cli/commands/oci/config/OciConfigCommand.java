package io.floci.cli.commands.oci.config;

import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
        name = "config",
        description = "Manage Floci OCI configuration",
        mixinStandardHelpOptions = true,
        subcommands = {
                OciConfigShowCommand.class,
                OciConfigValidateCommand.class,
                OciConfigProfileCommand.class,
                HelpCommand.class
        }
)
public class OciConfigCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
