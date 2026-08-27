package io.floci.cli.output;

/**
 * Renders {@code export KEY=VALUE} lines for the {@code env} commands of every product tree.
 *
 * <p>The output is documented as {@code eval} input, so values are single-quoted with per-shell
 * escaping — no interpolation of {@code $}, backticks, or quotes can occur even for hostile
 * {@code --endpoint} / {@code --host} / env-var values, and values containing {@code ;}
 * (the Azure connection string, OCI's {@code TF_VAR_CLIENT_HOST_OVERRIDES}) survive intact.
 */
public final class ShellExport {

    private ShellExport() {
    }

    /** Renders one export line for the given shell ({@code bash}, {@code fish}, {@code powershell}). */
    public static String formatExport(String shell, String key, String value) {
        return switch (shell.toLowerCase()) {
            // fish single quotes: only \' and \\ are escapes, backslash must be doubled first
            case "fish"               -> "set -x " + key + " '"
                    + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
            // PowerShell single quotes: literal except '' for a quote
            case "powershell", "ps1"  -> "$env:" + key + " = '" + value.replace("'", "''") + "'";
            // POSIX single quotes: close, escaped quote, reopen
            default                   -> "export " + key + "='" + value.replace("'", "'\\''") + "'";
        };
    }
}
