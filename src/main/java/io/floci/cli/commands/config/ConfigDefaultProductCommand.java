package io.floci.cli.commands.config;

import io.floci.cli.GlobalOptions;
import io.floci.cli.config.GlobalConfigStore;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import io.floci.cli.util.Causes;
import picocli.CommandLine.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "default-product",
        description = "Set the default product for bare commands like 'floci start'",
        mixinStandardHelpOptions = true
)
public class ConfigDefaultProductCommand implements Callable<Integer> {

    @Mixin
    GlobalOptions global;

    private final GlobalConfigStore store;

    public ConfigDefaultProductCommand() {
        this(new GlobalConfigStore());
    }

    /** Test seam: a store pointed at a temporary config file. */
    public ConfigDefaultProductCommand(GlobalConfigStore store) {
        this.store = store;
    }

    @Parameters(index = "0", description = "Product to use by default: aws, az, gcp, oci", paramLabel = "aws|az|gcp|oci")
    String product;

    @Override
    public Integer call() {
        Printer printer = global.printer();
        if (!"aws".equals(product) && !"az".equals(product) && !"gcp".equals(product) && !"oci".equals(product)) {
            printer.error("Unknown product '" + product + "'.\nUse one of: aws, az, gcp, oci.");
            return ExitCode.USAGE; // a value the command cannot accept, like any other bad argument
        }
        try {
            store.setDefaultProduct(product);
            if (printer.format() != OutputFormat.text) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("defaultProduct", product);
                result.put("file", store.configFilePath().toString());
                printer.structured(result);
                return 0;
            }
            printer.println(Ansi.green("Default product set to: ") + Ansi.bold(product));
            printer.println(Ansi.gray("'floci start' will now run: floci " + product + " start"));
            printer.println(Ansi.gray("Config saved to: " + store.configFilePath()));
            return 0;
        } catch (IOException e) {
            printer.error("Could not save config: " + Causes.firstLine(e),
                    "Check that " + store.configFilePath().getParent() + " is writable and re-run the command.");
            return 1;
        }
    }
}
