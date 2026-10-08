package io.floci.cli.doctor.checks;

import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;
import io.floci.cli.http.FlociHttpClient;

import java.time.Duration;

public class EndpointReachableCheck implements Check {

    @Override
    public String name() {
        return "endpoint.reachable";
    }

    private final String controlPrefix;

    public EndpointReachableCheck() {
        this(FlociHttpClient.DEFAULT_CONTROL_PREFIX);
    }

    public EndpointReachableCheck(String controlPrefix) {
        this.controlPrefix = controlPrefix;
    }

    @Override
    public CheckResult run(String endpoint, String container) {
        FlociHttpClient client = new FlociHttpClient(endpoint, controlPrefix);
        try {
            var health = client.health(Duration.ofSeconds(3));
            return CheckResult.ok("endpoint.reachable",
                    endpoint + " is reachable (server v" + health.version() + ")");
        } catch (Exception e) {
            // One request instead of a reachability probe followed by the same GET.
        }
        return CheckResult.fail("endpoint.reachable",
                "GET " + controlPrefix + "/health at " + endpoint + " did not return 200",
                "Is Floci running? Try 'floci start'.");
    }
}
