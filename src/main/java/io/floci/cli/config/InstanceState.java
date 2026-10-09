package io.floci.cli.config;

import io.floci.cli.ProductProfile;

import java.nio.file.Path;

/**
 * Where the CLI keeps local state about one emulator instance.
 *
 * <p>An instance is identified by its container name (a profile selects it through
 * {@code container:}), so two instances of the same product never share state:
 * {@code ~/.floci/<product>/<container>/}. Every command that keeps state about a specific
 * running emulator goes through here; see AGENTS.md, "Multiple instances".
 */
public final class InstanceState {

    private InstanceState() {
    }

    /** {@code ~/.floci}: the default root. */
    public static Path defaultRoot() {
        return Path.of(System.getProperty("user.home"), ".floci");
    }

    /** {@code ~/.floci/<product>/<container>/}. */
    public static Path dir(ProductProfile product, String container) {
        return dir(defaultRoot(), product, container);
    }

    /** As above under {@code root}; the test seam. */
    public static Path dir(Path root, ProductProfile product, String container) {
        return root.resolve(product.name()).resolve(validateContainer(container));
    }

    // Docker already restricts container names to [a-zA-Z0-9][a-zA-Z0-9_.-]*, but a name typed
    // into --container or a profile reaches here before docker sees it, and it becomes a path.
    static String validateContainer(String container) {
        if (container == null || container.isBlank() || ".".equals(container) || "..".equals(container)
                || container.contains("/") || container.contains("\\")) {
            throw new IllegalArgumentException("Invalid container name '" + container + "'.\n"
                    + "Use letters, digits, '_', '.' or '-', as docker does, and re-run the command.");
        }
        return container;
    }
}
