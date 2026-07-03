package io.floci.cli;

import io.floci.cli.commands.*;
import io.floci.cli.commands.aws.AwsCommand;
import io.floci.cli.commands.az.AzCommand;
import io.floci.cli.commands.gcp.GcpCommand;
import io.floci.cli.commands.config.ConfigCommand;
import io.floci.cli.commands.snapshot.SnapshotCommand;
import io.floci.cli.config.GlobalConfigStore;
import io.floci.cli.output.Ansi;
import io.floci.cli.update.UpdateNotifier;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(
        name = "floci",
        description = "Manage your local Floci emulators%n%n" +
                "  floci start     — launch the container (default: aws)%n" +
                "  floci stop      — stop the container%n" +
                "  floci status    — show health and version%n" +
                "  floci doctor    — diagnose environment issues%n" +
                "  floci env       — print environment variables%n" +
                "  floci aws       — explicit AWS emulator commands%n" +
                "  floci az        — Azure emulator commands%n" +
                "  floci gcp       — GCP emulator commands%n" +
                "  floci update    — Update the CLI to the latest release",
        mixinStandardHelpOptions = true,
        versionProvider = FlociCli.VersionProvider.class,
        subcommands = {
                StartCommand.class,
                StopCommand.class,
                RestartCommand.class,
                StatusCommand.class,
                LogsCommand.class,
                WaitCommand.class,
                VersionCommand.class,
                ServicesCommand.class,
                DoctorCommand.class,
                EnvCommand.class,
                ConfigCommand.class,
                SnapshotCommand.class,
                CompletionCommand.class,
                AwsCommand.class,
                AzCommand.class,
                GcpCommand.class,
                HelpCommand.class,
                UpdateCommand.class,
        }
)
public class FlociCli implements Runnable {

    static class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"floci " + VersionCommand.CLI_VERSION};
        }
    }

    static class ExceptionHandler implements IExecutionExceptionHandler {
        @Override
        public int handleExecutionException(Exception ex, CommandLine cmd, ParseResult parseResult) {
            System.err.println(Ansi.red("Error: ") + ex.getMessage());
            if (Boolean.getBoolean("floci.verbose")) {
                ex.printStackTrace(System.err);
            }
            return 1;
        }
    }

    public static void main(String[] args) {
        if (System.getenv("NO_COLOR") != null || System.console() == null) {
            Ansi.disable();
        }

        // If no explicit product subcommand, route bare commands to the configured default.
        // "floci az start" and "floci aws start" always win; only bare "floci start" is affected.
        String[] effectiveArgs = args;
        if (!isExplicitProduct(args)) {
            String defaultProduct = new GlobalConfigStore().getDefaultProduct();
            if ("az".equals(defaultProduct)) {
                effectiveArgs = prepend("az", args);
            } else if ("gcp".equals(defaultProduct)) {
                effectiveArgs = prepend("gcp", args);
            }
        }

        // npm/gh-style update hint: announce a newer version (from the local cache — no
        // network) before the command runs, and refresh that cache in the background at
        // most once per 24h. Skipped for scripts/CI (non-interactive) and for 'update'
        // itself, which already talks about versions.
        boolean notify = UpdateNotifier.interactiveRunEnabled() && !isUpdateCommand(effectiveArgs);
        if (notify) {
            UpdateNotifier.pendingNotice(VersionCommand.CLI_VERSION).ifPresent(latest ->
                    System.err.println(Ansi.yellow("A new release of floci is available: "
                            + VersionCommand.CLI_VERSION + " → " + latest + "\n"
                            + "Run 'floci update' to install it") + "\n"));
            UpdateNotifier.refreshInBackground();
        }

        int exitCode = new CommandLine(new FlociCli())
                .setExecutionExceptionHandler(new ExceptionHandler())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(effectiveArgs);

        if (notify) {
            // Fast commands would otherwise exit before the background check persists
            // the cache for the next run.
            UpdateNotifier.awaitRefresh(java.time.Duration.ofMillis(250));
        }
        System.exit(exitCode);
    }

    // Returns true when routing should NOT apply: explicit product subgroup,
    // or a product-independent command like "config" or "completion".
    private static boolean isExplicitProduct(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("-")) continue;
            return "aws".equals(arg) || "az".equals(arg) || "gcp".equals(arg)
                    || "config".equals(arg) || "completion".equals(arg) || "help".equals(arg);
        }
        return false;
    }

    private static boolean isUpdateCommand(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("-")) continue;
            return "update".equals(arg);
        }
        return false;
    }

    private static String[] prepend(String token, String[] args) {
        String[] result = new String[args.length + 1];
        result[0] = token;
        System.arraycopy(args, 0, result, 1, args.length);
        return result;
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
