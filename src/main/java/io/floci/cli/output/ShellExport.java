package io.floci.cli.output;

import java.util.Set;

/**
 * Renders {@code export KEY=VALUE} lines for the {@code env} commands of every product tree.
 *
 * <p>The output is documented as {@code eval} input, so values are single-quoted with per-shell
 * escaping — no interpolation of {@code $}, backticks, or quotes can occur even for hostile
 * {@code --endpoint} / {@code --host} / env-var values, and values containing {@code ;}
 * (the Azure connection string, OCI's {@code TF_VAR_CLIENT_HOST_OVERRIDES}) survive intact.
 */
public final class ShellExport {

    /** The {@code --shell} values the env commands accept. */
    public static final Set<String> SHELLS = Set.of("bash", "zsh", "sh", "fish", "powershell", "pwsh", "ps1");

    private ShellExport() {
    }

    public static boolean isSupported(String shell) {
        return shell != null && SHELLS.contains(shell.toLowerCase());
    }

    /** The error for an unsupported {@code --shell}, ending with what to pass instead. */
    public static String unsupported(String shell) {
        return "Unknown shell '" + shell + "'.\nUse --shell bash, zsh, sh, fish or powershell.";
    }

    /**
     * How to load {@code command}'s output in the given shell: {@code eval} only works in POSIX
     * shells, fish and PowerShell read the lines from a pipe instead.
     */
    public static String loadHint(String shell, String command) {
        return switch (shell.toLowerCase()) {
            case "fish"                       -> command + " --shell fish | source";
            case "powershell", "pwsh", "ps1"  -> command + " --shell powershell | Invoke-Expression";
            default                           -> "eval \"$(" + command + ")\"";
        };
    }

    /** Renders one export line for the given shell ({@code bash}, {@code fish}, {@code powershell}). */
    public static String formatExport(String shell, String key, String value) {
        return switch (shell.toLowerCase()) {
            // fish single quotes: only \' and \\ are escapes, backslash must be doubled first
            case "fish"               -> "set -x " + key + " '"
                    + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
            // PowerShell single quotes: literal except '' for a quote
            case "powershell", "pwsh", "ps1"  -> "$env:" + key + " = '" + value.replace("'", "''") + "'";
            // POSIX single quotes: close, escaped quote, reopen
            default                   -> "export " + key + "='" + value.replace("'", "'\\''") + "'";
        };
    }

    /** Renders the line that removes {@code key} from the environment in the given shell. */
    public static String formatUnset(String shell, String key) {
        return switch (shell.toLowerCase()) {
            case "fish"               -> "set -e " + key;
            case "powershell", "pwsh", "ps1"  -> "Remove-Item Env:" + key + " -ErrorAction SilentlyContinue";
            default                   -> "unset " + key;
        };
    }
}
