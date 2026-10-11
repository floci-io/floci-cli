package io.floci.cli;

import io.floci.cli.doctor.checks.ImageVersionCheck;
import io.floci.cli.update.Version;

import java.util.Optional;

/** Whether a running server is new enough for this CLI. */
public final class ServerCompatibility {

    private ServerCompatibility() {}

    /**
     * What to tell the user when {@code product}'s server reports {@code serverVersion}, or empty
     * when there is nothing to say. Only the AWS emulator has a minimum: the others are versioned
     * on their own lines (0.x), where the same number would flag every release. A version that
     * does not read as one ("unknown", a development build) is not judged.
     */
    public static Optional<String> warning(ProductProfile product, String serverVersion) {
        if (!"aws".equals(product.name()) || !Version.isValid(serverVersion)
                || ImageVersionCheck.meetsMinimum(serverVersion, ImageVersionCheck.MIN_VERSION)) {
            return Optional.empty();
        }
        return Optional.of(product.displayName() + " server " + serverVersion + " is older than "
                + ImageVersionCheck.MIN_VERSION + ", the oldest version this CLI supports; some commands may not work."
                + "\nRun '" + product.commandPrefix() + " stop --remove' and then '" + product.commandPrefix()
                + " start --pull always' to run a newer image.");
    }
}
