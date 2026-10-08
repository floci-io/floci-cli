package io.floci.cli.doctor.checks;

import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;

public class ImagePresentCheck implements Check {

    @Override
    public String name() {
        return "image.present";
    }

    private final String image;
    private final DockerClient docker;

    public ImagePresentCheck() {
        this("floci/floci");
    }

    public ImagePresentCheck(String image) {
        this(image, new DockerClient());
    }

    /** {@code docker} is shared across one doctor run so each docker fact is fetched once. */
    public ImagePresentCheck(String image, DockerClient docker) {
        this.image = image;
        this.docker = docker;
    }

    @Override
    public CheckResult run(String endpoint, String container) {
        try {
            if (docker.isImagePresent(image)) {
                return CheckResult.ok("image.present", image + " image present locally");
            }
            return CheckResult.fail("image.present",
                    image + " not pulled locally",
                    "floci start --pull always");
        } catch (DockerException e) {
            return CheckResult.warn("image.present", "Could not check image presence: " + e.getMessage(), null);
        }
    }
}
