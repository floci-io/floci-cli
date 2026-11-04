package io.floci.cli.config;

import io.floci.cli.ProductProfile;

/**
 * Maps a loaded {@link Profile} onto the CLI options it supplies defaults for.
 *
 * Kept as a pure function so the mapping can be table-tested without picocli:
 * {@link ProfileDefaultValueProvider} is the only adapter around it.
 */
public final class ProfileDefaults {

    private ProfileDefaults() {}

    public static String valueFor(Profile profile, String optionName, ProductProfile product) {
        return profile != null && "--endpoint".equals(optionName)
                ? endpointFor(profile, product) : valueFor(profile, optionName);
    }

    /** Legacy profiles may pair a custom host port with the product's default endpoint. */
    public static String endpointFor(Profile profile, ProductProfile product) {
        if (profile.port != null && (profile.endpoint == null
                || profile.endpoint.equals(product.defaultEndpoint()))) {
            return "http://localhost:" + profile.port;
        }
        return profile.endpoint;
    }

    /**
     * The profile's value for {@code optionName}, or {@code null} when the profile is silent
     * about that option — in which case picocli keeps the existing default.
     *
     * Option names are matched exactly. {@code --service} (singular, on wait/logs/env) is
     * deliberately NOT {@code --services}, and {@code --profile-name} (the OCI CLI profile
     * written by {@code floci oci setup}) is a different concept from {@code --profile}.
     */
    public static String valueFor(Profile profile, String optionName) {
        if (profile == null || optionName == null) return null;
        return switch (optionName) {
            case "--endpoint"  -> profile.endpoint;
            case "--container" -> profile.container;
            case "--image"     -> profile.image;
            case "--port"      -> profile.port == null ? null : String.valueOf(profile.port);
            case "--persist"   -> profile.persistDir;
            case "--services"  -> profile.services;
            case "--namespace" -> profile.namespace;
            case "--output"    -> profile.output;
            default -> null;
        };
    }
}
