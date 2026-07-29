package io.floci.cli.commands.config;

import io.floci.cli.GlobalOptions;
import io.floci.cli.config.GlobalConfigStore;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.concurrent.Callable;

@Command(
        name = "default-product",
        description = "Set the default product for bare commands like 'floci start'",
        mixinStandardHelpOptions = true
)
public class ConfigDefaultProductCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions global;

    @Parameters(index = "0", description = "Product to use by default: aws, az, gcp, oci", paramLabel = "aws|az|gcp|oci")
    String product;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        if (!"aws".equals(product) && !"az".equals(product) && !"gcp".equals(product) && !"oci".equals(product)) {
            printer.error("Unknown product '" + product + "'. Use: aws, az, gcp, oci");
            return 1;
        }
        GlobalConfigStore store = new GlobalConfigStore();
        try {
            store.setDefaultProduct(product);
            printer.println(Ansi.green("Default product set to: ") + Ansi.bold(product));
            printer.println(Ansi.gray("'floci start' will now run: floci " + product + " start"));
            printer.println(Ansi.gray("Config saved to: " + store.configFilePath()));
            return 0;
        } catch (IOException e) {
            printer.error("Could not save config: " + e.getMessage());
            return 1;
        }
    }
}
