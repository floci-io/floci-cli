package io.floci.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.floci.cli.ProductProfile;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Profile {
    public String name;
    public String endpoint;
    public String container;
    public String image;
    public Integer port;
    public String persistDir;
    public String services;
    public String output;

    public Profile() {}

    /** Seeds a new profile with the defaults of the product tree it was created under. */
    public Profile(ProductProfile product, String name) {
        this.name = name;
        this.endpoint = product.defaultEndpoint();
        this.container = product.defaultContainer();
        this.image = product.defaultImageRef();
        this.port = product.defaultPort();
    }
}
