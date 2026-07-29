package io.floci.cli.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FlociHttpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default control-plane path prefix used by the AWS and Azure emulators. */
    public static final String DEFAULT_CONTROL_PREFIX = "/_floci";

    private final String endpoint;
    private final String controlPrefix;
    private final HttpClient http;

    public FlociHttpClient(String endpoint) {
        this(endpoint, DEFAULT_CONTROL_PREFIX);
    }

    /**
     * @param controlPrefix the control-plane path prefix exposed by the target server
     *                      (e.g. {@code /_floci} for AWS/Azure, {@code /_floci-gcp} for GCP).
     */
    public FlociHttpClient(String endpoint, String controlPrefix) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.controlPrefix = controlPrefix.endsWith("/")
                ? controlPrefix.substring(0, controlPrefix.length() - 1)
                : controlPrefix;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public HealthInfo health() throws FlociException {
        JsonNode node = getJson(controlPrefix + "/health");
        return new HealthInfo(
                node.path("version").asText("unknown"),
                node.path("original_edition").asText(node.path("edition").asText("community")),
                serviceNames(node.path("services")));
    }

    // AWS/GCP/Azure report services as an array of names; OCI reports a
    // {name: status} object. Accept both.
    private static String[] serviceNames(JsonNode services) {
        if (services.isArray()) {
            return MAPPER.convertValue(services, String[].class);
        }
        if (services.isObject()) {
            List<String> names = new ArrayList<>();
            services.fieldNames().forEachRemaining(names::add);
            return names.toArray(String[]::new);
        }
        return new String[0];
    }

    public Optional<HealthInfo> healthOptional() {
        try {
            return Optional.of(health());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public ServerInfo info() throws FlociException {
        JsonNode node = getJson(controlPrefix + "/info");
        return new ServerInfo(
                node.path("version").asText("unknown"),
                node.path("original_edition").asText(node.path("edition").asText("community")));
    }

    public InitState initState() throws FlociException {
        JsonNode node = getJson(controlPrefix + "/init");
        JsonNode completed = node.path("completed");
        return new InitState(
                completed.path("boot").asBoolean(),
                completed.path("start").asBoolean(),
                completed.path("ready").asBoolean(),
                completed.path("shutdown").asBoolean());
    }

    public boolean isReachable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + controlPrefix + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Object> postSnapshot(String name) throws FlociException {
        return postJson(controlPrefix + "/snapshots/" + name, "{}");
    }

    public Map<String, Object> loadSnapshot(String name) throws FlociException {
        return postJson(controlPrefix + "/snapshots/" + name + "/load", "{}");
    }

    public JsonNode listSnapshots() throws FlociException {
        return getJson(controlPrefix + "/snapshots");
    }

    public void deleteSnapshot(String name) throws FlociException {
        deleteRequest(controlPrefix + "/snapshots/" + name);
    }

    private JsonNode getJson(String path) throws FlociException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new FlociException("Server returned HTTP " + resp.statusCode() + " for " + path);
            }
            return MAPPER.readTree(resp.body());
        } catch (FlociException e) {
            throw e;
        } catch (java.net.ConnectException e) {
            throw new FlociException("Connection refused at " + endpoint + ". Is Floci running? Try 'floci status' or 'floci start'.");
        } catch (Exception e) {
            throw new FlociException("Request failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String path, String body) throws FlociException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new FlociException("Server returned HTTP " + resp.statusCode() + " for " + path);
            }
            if (resp.body() == null || resp.body().isBlank()) return Map.of();
            return MAPPER.readValue(resp.body(), Map.class);
        } catch (FlociException e) {
            throw e;
        } catch (java.net.ConnectException e) {
            throw new FlociException("Connection refused at " + endpoint + ". Is Floci running? Try 'floci start'.");
        } catch (Exception e) {
            throw new FlociException("Request failed: " + e.getMessage());
        }
    }

    private void deleteRequest(String path) throws FlociException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + path))
                    .timeout(Duration.ofSeconds(10))
                    .DELETE()
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 400) {
                throw new FlociException("Server returned HTTP " + resp.statusCode() + " for " + path);
            }
        } catch (FlociException e) {
            throw e;
        } catch (Exception e) {
            throw new FlociException("Request failed: " + e.getMessage());
        }
    }

    public record HealthInfo(String version, String edition, String[] services) {}
    public record ServerInfo(String version, String edition) {}
    public record InitState(boolean boot, boolean start, boolean ready, boolean shutdown) {}
}
