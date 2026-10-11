package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.output.Printer;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

@Command(
        name = "completion",
        description = "Generate shell completion scripts",
        mixinStandardHelpOptions = true
)
public class CompletionCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions global;

    @Spec
    Model.CommandSpec spec;

    // Only the shells a script is generated for: picocli writes bash completion, which zsh loads
    // through bashcompinit. Listing fish and powershell here promised scripts that do not exist.
    @Parameters(index = "0", description = "Shell type: bash or zsh", paramLabel = "bash|zsh")
    String shell;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        // The installed shell command is always `floci`, and the root tree contains every
        // product subtree — so the root script is the correct completion no matter which
        // group (root, aws, gcp, az, oci) this command was invoked under.
        CommandLine root = spec.root().commandLine();

        String script = switch (shell.toLowerCase()) {
            case "bash" -> AutoComplete.bash("floci", root);
            case "zsh"  -> AutoComplete.bash("floci", root); // zsh is bash-compatible; picocli bash script works for zsh too
            default -> {
                printer.error("Shell '" + shell + "' is not supported.\nUse bash or zsh.");
                yield null;
            }
        };

        // A value the command cannot accept, like any other bad argument.
        if (script == null) return ExitCode.USAGE;
        System.out.print(script);
        return 0;
    }
}
