package io.floci.cli.doctor;

@FunctionalInterface
public interface Check {
    CheckResult run(String endpoint, String container);

    /**
     * The name this check reports under ({@code docker.installed}, ...), so {@code doctor --check}
     * can select it without running every other check first. {@code null} means unknown until
     * the check has run.
     */
    default String name() {
        return null;
    }
}
