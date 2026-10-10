package io.floci.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.floci.cli.output.Ansi;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public class GlobalConfigStore {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Path configFile;

    public GlobalConfigStore() {
        this(Path.of(System.getProperty("user.home"), ".floci", "config.yaml"));
    }

    public GlobalConfigStore(Path configFile) {
        this.configFile = configFile;
    }

    public String getDefaultProduct() {
        try {
            // Read first and ask afterwards: Files.exists also answers false for a file it is not
            // allowed to look at, which would skip the warning below.
            GlobalConfig config = YAML.readValue(Files.readAllBytes(configFile), GlobalConfig.class);
            String product = config == null ? null : config.defaultProduct;
            if ("az".equals(product)) return "az";
            if ("gcp".equals(product)) return "gcp";
            if ("oci".equals(product)) return "oci";
            return "aws";
        } catch (NoSuchFileException e) {
            return "aws"; // never configured
        } catch (IOException e) {
            // Not silent: every bare command would otherwise switch to AWS without a word.
            System.err.println(Ansi.yellow("Warning: ") + "Could not read " + configFile + " (" + reason(e)
                    + "), so the default product is aws."
                    + "\nCheck the file and its permissions, or run 'floci config default-product <aws|gcp|az|oci>' to rewrite it.");
            return "aws";
        }
    }

    private static String reason(IOException e) {
        if (e instanceof AccessDeniedException) return "permission denied";
        String message = e.getMessage();
        if (message == null) return e.getClass().getSimpleName();
        int newline = message.indexOf('\n');
        return newline >= 0 ? message.substring(0, newline) : message;
    }

    public void setDefaultProduct(String product) throws IOException {
        GlobalConfig config = new GlobalConfig();
        config.defaultProduct = product;
        AtomicFiles.write(configFile, YAML.writeValueAsBytes(config));
    }

    public Path configFilePath() {
        return configFile;
    }

    // Unknown keys are ignored, so a file written by a newer CLI still yields its default product.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GlobalConfig {
        @JsonProperty("default-product")
        public String defaultProduct = "aws";
    }
}
