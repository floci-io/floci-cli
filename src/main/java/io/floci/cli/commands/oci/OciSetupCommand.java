package io.floci.cli.commands.oci;

import io.floci.cli.OciGlobalOptions;
import io.floci.cli.output.Ansi;
import io.floci.cli.output.OutputFormat;
import io.floci.cli.output.Printer;
import picocli.CommandLine.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "setup",
        description = "Create a local OCI CLI profile (API key + ~/.oci/config) for the emulator",
        mixinStandardHelpOptions = true
)
public class OciSetupCommand implements Callable<Integer> {

    // Canonical throwaway identity documented by floci-oci; the emulator partitions
    // state by the tenancy OCID but never validates the credentials themselves.
    static final String TENANCY = "ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000";
    static final String USER = "ocid1.user.oc1..flocilocaluser0000000000000000000000000000000000000000000000";
    static final String REGION = "us-ashburn-1";
    static final String KEY_FILE_NAME = "floci_key.pem";

    @Mixin
    OciGlobalOptions global;

    @Option(names = {"--profile-name"},
            description = "Profile section name to write in ~/.oci/config (default: FLOCI)",
            defaultValue = "FLOCI",
            paramLabel = "<name>")
    String profileName;

    private final Path ociDir;

    public OciSetupCommand() {
        this(Path.of(System.getProperty("user.home"), ".oci"));
    }

    public OciSetupCommand(Path ociDir) {
        this.ociDir = ociDir;
    }

    @Override
    public Integer call() {
        Printer printer = global.printer();
        Path keyFile = ociDir.resolve(KEY_FILE_NAME);
        Path configFile = ociDir.resolve("config");

        boolean keyCreated;
        boolean profileAdded;
        String fingerprint;
        try {
            Files.createDirectories(ociDir);
            restrictPermissions(ociDir, "rwx------");

            keyCreated = !Files.exists(keyFile);
            if (keyCreated) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                Files.writeString(keyFile, toPem(pair.getPrivate().getEncoded()));
                restrictPermissions(keyFile, "rw-------");
            }
            fingerprint = fingerprint(keyFile);

            profileAdded = !profileExists(configFile, profileName);
            if (profileAdded) {
                String section = (Files.exists(configFile) ? "\n" : "")
                        + "[" + profileName + "]\n"
                        + "user=" + USER + "\n"
                        + "fingerprint=" + fingerprint + "\n"
                        + "tenancy=" + TENANCY + "\n"
                        + "region=" + REGION + "\n"
                        + "key_file=" + keyFile.toAbsolutePath() + "\n";
                Files.writeString(configFile, section,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                restrictPermissions(configFile, "rw-------");
            }
        } catch (Exception e) {
            printer.error("Could not set up the OCI profile: " + e.getMessage()
                    + "\nCheck that " + ociDir + " is writable, or remove a corrupt "
                    + KEY_FILE_NAME + " and re-run 'floci oci setup'.");
            return 1;
        }

        if (printer.format() != OutputFormat.text) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("keyFile", keyFile.toAbsolutePath().toString());
            out.put("keyCreated", keyCreated);
            out.put("configFile", configFile.toAbsolutePath().toString());
            out.put("profile", profileName);
            out.put("profileAdded", profileAdded);
            out.put("fingerprint", fingerprint);
            printer.structured(out);
            return 0;
        }

        printer.println((keyCreated ? Ansi.green("Created ") : Ansi.gray("Reusing "))
                + keyFile.toAbsolutePath() + Ansi.gray(" (RSA 2048)"));
        printer.println((profileAdded ? Ansi.green("Added profile ") : Ansi.gray("Profile already present: "))
                + Ansi.bold("[" + profileName + "]") + " in " + configFile.toAbsolutePath());
        printer.println("");
        printer.println("Connect with:");
        printer.println("  " + Ansi.bold("eval $(floci oci env)"));
        printer.println("  " + Ansi.bold("oci os ns get"));
        return 0;
    }

    static boolean profileExists(Path configFile, String profileName) throws Exception {
        return Files.exists(configFile)
                && Files.readAllLines(configFile).stream()
                        .map(String::trim)
                        .anyMatch(("[" + profileName + "]")::equals);
    }

    /**
     * Finds the profile section this command wrote, whatever {@code --profile-name} was used:
     * the section whose {@code key_file} points at the setup-generated key. Prefers
     * {@code FLOCI} (the default name) when several sections share the key.
     * Returns {@code null} when no setup-written profile exists.
     */
    public static String detectSetupProfile(Path configFile) throws Exception {
        if (!Files.exists(configFile)) return null;
        String section = null;
        String found = null;
        for (String raw : Files.readAllLines(configFile)) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                continue;
            }
            if (section == null || !line.startsWith("key_file")) continue;
            String[] kv = line.split("=", 2);
            if (kv.length == 2 && kv[1].trim().endsWith(KEY_FILE_NAME)) {
                if ("FLOCI".equals(section)) return section;
                if (found == null) found = section;
            }
        }
        return found;
    }

    private static String toPem(byte[] pkcs8) {
        // 64-char lines per RFC 7468; the trailing OCI_API_KEY label is the marker the
        // OCI CLI looks for to confirm the key is API-only (suppresses its warning).
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pkcs8);
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\nOCI_API_KEY\n";
    }

    /** OCI API-key fingerprint: colon-separated MD5 of the DER-encoded public key. */
    private static String fingerprint(Path keyFile) throws Exception {
        String pem = Files.readString(keyFile)
                .replaceAll("-----[A-Z ]+-----", "")
                .replace("OCI_API_KEY", "")
                .replaceAll("\\s", "");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        byte[] publicDer = KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent()))
                .getEncoded();
        byte[] digest = MessageDigest.getInstance("MD5").digest(publicDer);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            if (sb.length() > 0) sb.append(':');
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void restrictPermissions(Path path, String posix) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posix));
        } catch (UnsupportedOperationException ignored) {
            // Windows: no POSIX permissions; ~/.oci inherits the user's ACLs.
        } catch (Exception ignored) {
        }
    }
}
