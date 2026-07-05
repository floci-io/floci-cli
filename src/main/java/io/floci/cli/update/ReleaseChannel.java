package io.floci.cli.update;

/**
 * Where releases live: the public GitHub repository, same source as installer/install.sh.
 * Both URLs are env-overridable so tests can point at a local server and mirrors work
 * in air-gapped setups.
 */
public final class ReleaseChannel {

    public static final String REPO = "floci-io/floci-cli";

    /** Overrides the GitHub API base URL (used to resolve the latest release tag). */
    public static final String API_ENV = "FLOCI_UPDATE_API_URL";
    /** Overrides the release-asset download base URL. */
    public static final String DOWNLOAD_ENV = "FLOCI_UPDATE_DOWNLOAD_URL";

    private ReleaseChannel() {
    }

    /** GET this to resolve the latest release; the JSON's {@code tag_name} is the version. */
    public static String latestReleaseUrl() {
        String base = System.getenv().getOrDefault(API_ENV, "https://api.github.com");
        return base + "/repos/" + REPO + "/releases/latest";
    }

    /** Download URL for one asset of one release, e.g. {@code floci-darwin-arm64}. */
    public static String assetUrl(String version, String asset) {
        String base = System.getenv().getOrDefault(
                DOWNLOAD_ENV, "https://github.com/" + REPO + "/releases/download");
        return base + "/" + version + "/" + asset;
    }
}
