package io.floci.cli;

import java.io.InputStream;
import java.util.Properties;

/** The CLI's own version, read from version.properties stamped at build time. */
public final class CliVersion {

    public static final String CLI_VERSION = loadVersion();

    private static String loadVersion() {
        try (InputStream is = CliVersion.class.getResourceAsStream("/io/floci/cli/version.properties")) {
            if (is == null) return "unknown";
            Properties props = new Properties();
            props.load(is);
            return props.getProperty("version", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private CliVersion() {}
}
