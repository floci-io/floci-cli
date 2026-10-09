package io.floci.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.floci.cli.output.Ansi;

import java.io.IOException;
import java.nio.file.Files;
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
        if (!Files.exists(configFile)) return "aws";
        try {
            GlobalConfig config = YAML.readValue(configFile.toFile(), GlobalConfig.class);
            if ("az".equals(config.defaultProduct)) return "az";
            if ("gcp".equals(config.defaultProduct)) return "gcp";
            if ("oci".equals(config.defaultProduct)) return "oci";
            return "aws";
        } catch (IOException e) {
            // Not silent: every bare command would otherwise switch to AWS without a word.
            System.err.println(Ansi.yellow("Warning: ") + "Could not read " + configFile + ", so the default product is aws."
                    + "\nFix the file, or run 'floci config default-product <aws|gcp|az|oci>' to rewrite it.");
            return "aws";
        }
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
