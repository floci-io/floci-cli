package io.floci.cli.unit;

import io.floci.cli.FlociCli;
import io.floci.cli.GlobalOptions;
import io.floci.cli.ProductProfile;
import io.floci.cli.config.ProfileStore;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.ParseResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class GlobalOptionsTest {

    private static UnaryOperator<String> env(Map<String, String> vars) {
        return vars::get;
    }

    @Test
    void productDefaultsWhenEnvUnset() {
        GlobalOptions gcp = new GlobalOptions(ProductProfile.GCP, env(Map.of()));
        assertEquals("http://localhost:4588", gcp.endpoint);
        assertEquals("floci-gcp", gcp.container);

        GlobalOptions oci = new GlobalOptions(ProductProfile.OCI, env(Map.of()));
        assertEquals("http://localhost:4599", oci.endpoint);
        assertEquals("floci-oci", oci.container);
    }

    @Test
    void envVarOverridesDefault() {
        GlobalOptions az = new GlobalOptions(ProductProfile.AZ,
                env(Map.of("FLOCI_AZ_ENDPOINT", "http://remote:9999", "FLOCI_AZ_CONTAINER", "custom")));
        assertEquals("http://remote:9999", az.endpoint);
        assertEquals("custom", az.container);
    }

    @Test
    void wrongProductEnvVarIsIgnored() {
        GlobalOptions gcp = new GlobalOptions(ProductProfile.GCP,
                env(Map.of("FLOCI_ENDPOINT", "http://aws-only:1")));
        assertEquals("http://localhost:4588", gcp.endpoint);
    }

    @Test
    void emptyEnvVarCountsAsSet() {
        // Matches picocli ${VAR:-fallback} semantics: only null falls back.
        GlobalOptions aws = new GlobalOptions(ProductProfile.AWS,
                env(Map.of("FLOCI_ENDPOINT", "")));
        assertEquals("", aws.endpoint);
    }

    @Test
    void noArgConstructorIsAws() {
        GlobalOptions options = new GlobalOptions();
        assertEquals(ProductProfile.AWS, options.product);
        // Cannot assert endpoint literal here: real env may set FLOCI_ENDPOINT.
    }

    @Test
    void endpointFromPortsUsesProductFallbackPortForPortlessEndpoint() {
        // Endpoint URL without a port → the product's default port must be matched.
        for (ProductProfile p : new ProductProfile[]{
                ProductProfile.AWS, ProductProfile.GCP, ProductProfile.AZ, ProductProfile.OCI}) {
            GlobalOptions options = new GlobalOptions(p, env(Map.of()));
            String result = options.endpointFromPorts(
                    "12345->" + p.defaultPort() + "/tcp", "http://localhost");
            assertEquals("http://localhost:12345", result, p.name());
        }
    }

    @Test
    void endpointFromPortsMatchesExplicitPort() {
        GlobalOptions gcp = new GlobalOptions(ProductProfile.GCP, env(Map.of()));
        assertEquals("http://localhost:9999",
                gcp.endpointFromPorts("9999->4588/tcp", "http://localhost:4588"));
        assertEquals("http://localhost:4588",
                gcp.endpointFromPorts("9999->1111/tcp", "http://localhost:4588"));
    }

    @Test
    void instanceSelectorNamesTheProfileOrANonDefaultContainer() {
        GlobalOptions az = new GlobalOptions(ProductProfile.AZ, env(Map.of()));
        assertEquals("", az.instanceSelector());

        az.container = "floci-az-b";
        assertEquals(" --container floci-az-b", az.instanceSelector());

        az.profile = "team-b";
        assertEquals(" --profile team-b", az.instanceSelector());
    }

    /** Hints are pasted into a shell: a profile name with a space must survive as one word. */
    @Test
    void instanceSelectorQuotesNamesThatAreNotPlainWords() throws Exception {
        Path store = Files.createTempDirectory("floci-profiles");
        Files.writeString(store.resolve("team alpha.yaml"), "container: floci-az-a\n");

        GlobalOptions quoted = azOptions(store, "--profile", "team alpha");
        assertEquals(" --profile 'team alpha'", quoted.instanceSelector());

        GlobalOptions withContainer = azOptions(store, "--profile", "team alpha", "--container", "floci-az-b");
        assertEquals(" --profile 'team alpha' --container floci-az-b", withContainer.instanceSelector());
    }

    private static GlobalOptions azOptions(Path store, String... flags) {
        String[] args = new String[flags.length + 2];
        args[0] = "az";
        args[1] = "env";
        System.arraycopy(flags, 0, args, 2, flags.length);
        ParseResult r = FlociCli.buildCommandLine(new ProfileStore(store)).parseArgs(args);
        while (r.hasSubcommand()) r = r.subcommand();
        return (GlobalOptions) r.commandSpec().mixins().get("global").userObject();
    }
}
