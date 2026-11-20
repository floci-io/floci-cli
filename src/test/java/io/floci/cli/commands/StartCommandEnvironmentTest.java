package io.floci.cli.commands;

import io.floci.cli.ProductProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartCommandEnvironmentTest {
    @Test
    void awsXksDeploymentKeyIsPassedByVariableNameOnly() {
        List<String> args = new ArrayList<>();

        StartCommand.addXksCredentialKeyPassThrough(args, ProductProfile.AWS, "deployment-secret");

        assertEquals(List.of("-e", "FLOCI_SERVICES_KMS_XKS_CREDENTIAL_ENCRYPTION_KEY"), args);
    }

    @Test
    void xksDeploymentKeyIsNotForwardedForOtherProductsOrWhenUnset() {
        List<String> args = new ArrayList<>();

        StartCommand.addXksCredentialKeyPassThrough(args, ProductProfile.GCP, "deployment-secret");
        StartCommand.addXksCredentialKeyPassThrough(args, ProductProfile.AWS, null);

        assertEquals(List.of(), args);
    }
}
