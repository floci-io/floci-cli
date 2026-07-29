package io.floci.cli;

/**
 * The single source of per-product configuration for the unified command tree.
 * Every value that differs between the AWS, GCP, Azure, and OCI emulators lives here;
 * command classes are parameterized with one of the four constants below.
 */
public record ProductProfile(
        String name,             // CLI group name: "aws" | "gcp" | "az" | "oci"
        String displayName,      // banner name: "Floci" | "Floci GCP" | "Floci Azure" | "Floci OCI"
        String image,            // Docker image without tag
        String defaultContainer,
        int defaultPort,
        String envPrefix,        // FLOCI | FLOCI_GCP | FLOCI_AZ | FLOCI_OCI
        String controlPrefix     // control-plane path prefix on the server
) {

    public String defaultEndpoint() {
        return "http://localhost:" + defaultPort;
    }

    public String defaultImageRef() {
        return image + ":latest";
    }

    /** {@code envVar("SERVICES")} → {@code FLOCI_GCP_SERVICES} */
    public String envVar(String suffix) {
        return envPrefix + "_" + suffix;
    }

    /** Command prefix for hint strings: "floci" for aws (the bare tree), "floci gcp" otherwise. */
    public String commandPrefix() {
        return "aws".equals(name) ? "floci" : "floci " + name;
    }

    /** GitHub repository of the product's emulator server, for issue-tracker hints. */
    public String serverRepo() {
        return "https://github.com/floci-io/" + ("aws".equals(name) ? "floci" : "floci-" + name);
    }

    public static final ProductProfile AWS = new ProductProfile(
            "aws", "Floci AWS", "floci/floci", "floci", 4566, "FLOCI", "/_floci");

    public static final ProductProfile GCP = new ProductProfile(
            "gcp", "Floci GCP", "floci/floci-gcp", "floci-gcp", 4588, "FLOCI_GCP", "/_floci-gcp");

    // AZ intentionally shares the AWS control prefix /_floci — the floci-az server
    // exposes /_floci/*, NOT /_floci-az. Do not "fix" this to match the name.
    public static final ProductProfile AZ = new ProductProfile(
            "az", "Floci Azure", "floci/floci-az", "floci-az", 4577, "FLOCI_AZ", "/_floci");

    public static final ProductProfile OCI = new ProductProfile(
            "oci", "Floci OCI", "floci/floci-oci", "floci-oci", 4599, "FLOCI_OCI", "/_floci-oci");
}
