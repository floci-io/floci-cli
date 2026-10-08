package io.floci.cli.doctor.checks;

import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;

public class AzCliInstalledCheck implements Check {

    @Override
    public String name() {
        return "az.cli.installed";
    }

    @Override
    public CheckResult run(String endpoint, String container) {
        try {
            Process p = new ProcessBuilder("az", "--version").start();
            if (p.waitFor() == 0) {
                return CheckResult.ok("az.cli.installed", "az CLI found");
            }
            return CheckResult.warn("az.cli.installed", "az CLI not found in PATH",
                    "Install from https://learn.microsoft.com/en-us/cli/azure/install-azure-cli");
        } catch (Exception e) {
            return CheckResult.warn("az.cli.installed", "az CLI not found in PATH",
                    "Install from https://learn.microsoft.com/en-us/cli/azure/install-azure-cli");
        }
    }
}
