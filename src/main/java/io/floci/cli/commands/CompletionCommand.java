package io.floci.cli.commands;

import io.floci.cli.GlobalOptions;
import io.floci.cli.output.Printer;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.util.List;
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
            // zsh loads picocli's bash script through bashcompinit.
            case "zsh"  -> forZsh(AutoComplete.bash("floci", root));
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

    /**
     * Lines picocli's script runs at the top level of a zsh, which change the user's shell rather
     * than floci's completion (#82). {@code COMPLETE_ALIASES} stays on for the whole shell, so every
     * alias completes as a command of its own instead of as what it stands for. The {@code compopt}
     * alias is left behind too, and it gets expanded into the completion functions as
     * {@code complete +o default}, which bashcompinit's {@code complete} turns into completions for
     * commands named {@code +o} and {@code default}. Neither does anything for floci's own
     * completion: bashcompinit has no {@code compopt}, and the functions skip it when it is absent.
     */
    public static final List<String> ZSH_SHELL_CHANGES = List.of("  setopt COMPLETE_ALIASES", "  alias compopt=complete");

    /** {@code bashScript} without {@link #ZSH_SHELL_CHANGES}. */
    public static String forZsh(String bashScript) {
        StringBuilder out = new StringBuilder(bashScript.length());
        for (String line : bashScript.split("\n", -1)) {
            if (ZSH_SHELL_CHANGES.contains(line.stripTrailing())) continue;
            out.append(line).append('\n');
        }
        // split(-1) yields a final empty element for a trailing newline; drop the one it added.
        out.setLength(out.length() - 1);
        return out.toString();
    }
}
