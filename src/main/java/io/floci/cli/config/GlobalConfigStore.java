package io.floci.cli.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

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
            return "aws";
        }
    }

    public void setDefaultProduct(String product) throws IOException {
        Files.createDirectories(configFile.getParent());
        GlobalConfig config = new GlobalConfig();
        config.defaultProduct = product;
        YAML.writeValue(configFile.toFile(), config);
    }

    public Path configFilePath() {
        return configFile;
    }

    public static class GlobalConfig {
        @JsonProperty("default-product")
        public String defaultProduct = "aws";
    }
}
