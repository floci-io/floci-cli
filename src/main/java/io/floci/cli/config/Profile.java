package io.floci.cli.config;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.floci.cli.ProductProfile;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Profile {
    public String name;
    public String endpoint;
    public String container;
    public String image;
    public Integer port;
    public String persistDir;
    public String services;
    public String namespace;
    public String output;

    /** The keys a profile file may set: every field above. Pinned against them by a test. */
    public static final List<String> KNOWN_KEYS = List.of(
            "name", "endpoint", "container", "image", "port", "persistDir", "services", "namespace", "output");

    // Unknown keys stay readable for forward compatibility, but are recorded so a misspelled one
    // ('persist_dir') can be reported instead of silently dropping the setting.
    @JsonIgnore
    private final List<String> unknownKeys = new ArrayList<>();

    public Profile() {}

    @JsonAnySetter
    void unknownKey(String key, Object value) {
        unknownKeys.add(key);
    }

    /** Keys the file set that no field reads, in file order. */
    public List<String> unknownKeys() {
        return List.copyOf(unknownKeys);
    }

    /** Seeds a new profile with the defaults of the product tree it was created under. */
    public Profile(ProductProfile product, String name) {
        this.name = name;
        this.endpoint = product.defaultEndpoint();
        this.container = product.defaultContainer();
        this.image = product.defaultImageRef();
        this.port = product.defaultPort();
    }
}
