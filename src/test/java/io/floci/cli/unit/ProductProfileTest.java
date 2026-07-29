package io.floci.cli.unit;

import io.floci.cli.ProductProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pins every field of the four product constants — the drift firewall for the unified tree. */
class ProductProfileTest {

    @Test
    void aws() {
        assertEquals("aws", ProductProfile.AWS.name());
        assertEquals("Floci AWS", ProductProfile.AWS.displayName());
        assertEquals("floci/floci", ProductProfile.AWS.image());
        assertEquals("floci/floci:latest", ProductProfile.AWS.defaultImageRef());
        assertEquals("floci", ProductProfile.AWS.defaultContainer());
        assertEquals(4566, ProductProfile.AWS.defaultPort());
        assertEquals("http://localhost:4566", ProductProfile.AWS.defaultEndpoint());
        assertEquals("FLOCI_ENDPOINT", ProductProfile.AWS.envVar("ENDPOINT"));
        assertEquals("/_floci", ProductProfile.AWS.controlPrefix());
        assertEquals("floci", ProductProfile.AWS.commandPrefix());
    }

    @Test
    void gcp() {
        assertEquals("gcp", ProductProfile.GCP.name());
        assertEquals("Floci GCP", ProductProfile.GCP.displayName());
        assertEquals("floci/floci-gcp", ProductProfile.GCP.image());
        assertEquals("floci-gcp", ProductProfile.GCP.defaultContainer());
        assertEquals(4588, ProductProfile.GCP.defaultPort());
        assertEquals("FLOCI_GCP_CONTAINER", ProductProfile.GCP.envVar("CONTAINER"));
        assertEquals("/_floci-gcp", ProductProfile.GCP.controlPrefix());
        assertEquals("floci gcp", ProductProfile.GCP.commandPrefix());
    }

    @Test
    void az() {
        assertEquals("az", ProductProfile.AZ.name());
        assertEquals("Floci Azure", ProductProfile.AZ.displayName());
        assertEquals("floci/floci-az", ProductProfile.AZ.image());
        assertEquals("floci-az", ProductProfile.AZ.defaultContainer());
        assertEquals(4577, ProductProfile.AZ.defaultPort());
        assertEquals("FLOCI_AZ_ENDPOINT", ProductProfile.AZ.envVar("ENDPOINT"));
        // The floci-az server exposes /_floci/* (same as AWS), NOT /_floci-az.
        assertEquals("/_floci", ProductProfile.AZ.controlPrefix());
        assertEquals("floci az", ProductProfile.AZ.commandPrefix());
    }

    @Test
    void oci() {
        assertEquals("oci", ProductProfile.OCI.name());
        assertEquals("Floci OCI", ProductProfile.OCI.displayName());
        assertEquals("floci/floci-oci", ProductProfile.OCI.image());
        assertEquals("floci-oci", ProductProfile.OCI.defaultContainer());
        assertEquals(4599, ProductProfile.OCI.defaultPort());
        assertEquals("FLOCI_OCI_ENDPOINT", ProductProfile.OCI.envVar("ENDPOINT"));
        assertEquals("/_floci-oci", ProductProfile.OCI.controlPrefix());
        assertEquals("floci oci", ProductProfile.OCI.commandPrefix());
    }
}
