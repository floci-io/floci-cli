package io.floci.cli.unit;

import io.floci.cli.config.Profile;
import io.floci.cli.config.ProfileDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Pins which CLI option each Profile field feeds, and which options it must leave alone. */
class ProfileDefaultsTest {

    private static Profile fullProfile() {
        Profile p = new Profile();
        p.name = "probe";
        p.endpoint = "http://localhost:4566";
        p.container = "floci-probe";
        p.image = "floci/floci:enforced";
        p.port = 4599;
        p.persistDir = "/tmp/floci-persist-test";
        p.services = "s3,lambda";
        p.output = "json";
        return p;
    }

    @ParameterizedTest
    @CsvSource({
            "--endpoint,  http://localhost:4566",
            "--container, floci-probe",
            "--image,     floci/floci:enforced",
            "--port,      4599",
            "--persist,   /tmp/floci-persist-test",
            "--services,  's3,lambda'",
            "--output,    json",
    })
    void mapsEveryProfileField(String option, String expected) {
        assertEquals(expected, ProfileDefaults.valueFor(fullProfile(), option), option);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "--service",       // wait/logs/env filter — NOT the profile's 'services'
            "--profile-name",  // the OCI CLI profile written by 'floci oci setup'
            "--profile",
            "--pull",
            "--timeout",
            "--file",
    })
    void leavesUnrelatedOptionsAlone(String option) {
        assertNull(ProfileDefaults.valueFor(fullProfile(), option), option);
    }

    @Test
    void unsetFieldsAreSilentSoPicocliKeepsItsOwnDefault() {
        Profile empty = new Profile();
        for (String option : new String[]{"--endpoint", "--container", "--image", "--port", "--persist", "--services", "--output"}) {
            assertNull(ProfileDefaults.valueFor(empty, option), option);
        }
    }

    @Test
    void nullInputsAreSilent() {
        assertNull(ProfileDefaults.valueFor(null, "--endpoint"));
        assertNull(ProfileDefaults.valueFor(fullProfile(), null));
    }
}
