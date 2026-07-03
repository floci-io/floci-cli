package io.floci.cli.update;

/** Persisted shape of {@code ~/.floci/update-check.json}. */
public record UpdateCache(long checkedAtEpochSeconds, String latestVersion) {
}
