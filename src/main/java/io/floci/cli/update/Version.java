package io.floci.cli.update;

import java.util.regex.Pattern;

/** Minimal semver helpers for the update check — no ranges, just "is this newer". */
public final class Version {

    // x.y.z with an optional pre-release/build suffix; tolerates a leading "v".
    private static final Pattern SHAPE = Pattern.compile("v?\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?");

    private Version() {
    }

    /** Whether {@code s} looks like a release version at all (guards cache poisoning). */
    public static boolean isValid(String s) {
        return s != null && SHAPE.matcher(s).matches();
    }

    /** Numeric compare of the x.y.z core; a pre-release suffix on equal cores sorts older. */
    public static boolean isNewer(String candidate, String current) {
        if (!isValid(candidate) || !isValid(current)) {
            return false;
        }
        int[] a = core(candidate);
        int[] b = core(current);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        // Equal cores: only "1.2.3" is newer than "1.2.3-rc.1", never the reverse.
        return hasSuffix(current) && !hasSuffix(candidate);
    }

    private static int[] core(String v) {
        String stripped = v.startsWith("v") ? v.substring(1) : v;
        String[] parts = stripped.split("[-+]", 2)[0].split("\\.");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }

    private static boolean hasSuffix(String v) {
        return v.contains("-") || v.contains("+");
    }
}
