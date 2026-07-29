package io.floci.cli.unit;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.DoctorCommand;
import io.floci.cli.doctor.Check;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the per-product doctor check composition and order (Docker checks first, then companions). */
class DoctorCheckListTest {

    private static final List<String> BASE = List.of(
            "DockerInstalledCheck", "DockerDaemonCheck", "DockerSocketCheck", "DockerVersionCheck",
            "PortAvailableCheck", "ImagePresentCheck", "ImageVersionCheck",
            "ContainerRunningCheck", "EndpointReachableCheck");

    private static List<String> names(List<Check> checks) {
        return checks.stream().map(c -> c.getClass().getSimpleName()).toList();
    }

    @Test
    void awsHasBaseChecksPlusAwsCliCompanions() {
        var names = names(new DoctorCommand().allChecks());
        assertEquals(BASE.size() + 2, names.size());
        assertEquals(BASE, names.subList(0, BASE.size()));
        assertEquals(List.of("AwsCliEndpointCheck", "AwsCliS3PathStyleCheck"), names.subList(BASE.size(), names.size()));
    }

    @Test
    void azHasBaseChecksPlusAzCliCompanions() {
        var names = names(new DoctorCommand(ProductProfile.AZ, DoctorCommand.AZ_COMPANION_CHECKS).allChecks());
        assertEquals(BASE, names.subList(0, BASE.size()));
        assertEquals(List.of("AzCliInstalledCheck", "AzCliConnectionStringCheck"), names.subList(BASE.size(), names.size()));
    }

    @Test
    void gcpAndOciHaveExactlyTheBaseChecks() {
        for (ProductProfile p : List.of(ProductProfile.GCP, ProductProfile.OCI)) {
            assertEquals(BASE, names(new DoctorCommand(p, List.of()).allChecks()), p.name());
        }
    }
}
