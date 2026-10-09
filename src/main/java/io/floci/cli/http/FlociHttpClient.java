package io.floci.cli.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class FlociHttpClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default control-plane path prefix used by the AWS and Azure emulators. */
    public static final String DEFAULT_CONTROL_PREFIX = "/_floci";

    private final String endpoint;
    private final String query;
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
        // Request paths go before an endpoint's query: http://h:1/?x=1 + /health is http://h:1/health?x=1.
        // A fragment is never sent, so it is dropped.
        int fragment = endpoint.indexOf('#');
        String base = fragment >= 0 ? endpoint.substring(0, fragment) : endpoint;
        int query = base.indexOf('?');
        this.query = query >= 0 ? base.substring(query) : "";
        base = query >= 0 ? base.substring(0, query) : base;
        this.endpoint = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.controlPrefix = controlPrefix.endsWith("/")
                ? controlPrefix.substring(0, controlPrefix.length() - 1)
                : controlPrefix;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public HealthInfo health() throws FlociException {
        return health(Duration.ofSeconds(10));
    }

    /** {@link #health()} with its own request timeout, for probes that must answer quickly. */
    public HealthInfo health(Duration timeout) throws FlociException {
        JsonNode node = getJson(controlPrefix + "/health", timeout);
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
                    .uri(url(controlPrefix + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The PEM CA certificate the server's HTTPS listener chains to. Only floci-az serves it, and
     * only with TLS enabled; a 404 becomes {@link TlsUnavailableException}.
     */
    public String tlsCert() throws FlociException {
        String path = controlPrefix + "/tls-cert";
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(url(path))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                String message = "";
                try {
                    message = MAPPER.readTree(resp.body()).path("message").asText("");
                } catch (Exception ignored) {
                    // Not JSON: an older server without the endpoint at all.
                }
                throw new TlsUnavailableException(
                        message.isEmpty() ? "TLS certificate not available at " + path : message,
                        message.contains("not available yet"));
            }
            if (resp.statusCode() >= 400) {
                throw new FlociException("Server returned HTTP " + resp.statusCode() + " for " + path);
            }
            return resp.body();
        } catch (FlociException e) {
            throw e;
        } catch (ConnectException e) {
            throw new FlociException("Connection refused at " + endpoint + ". Is Floci running? Try 'floci status' or 'floci start'.");
        } catch (Exception e) {
            throw new FlociException("Request failed: " + e.getMessage());
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
        return getJson(path, Duration.ofSeconds(10));
    }

    private JsonNode getJson(String path, Duration timeout) throws FlociException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(url(path))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            // HttpRequest.timeout stops counting once the headers arrive, so a server that stalls
            // mid-body would outlive it; the whole exchange is bounded here and cancelled on expiry.
            CompletableFuture<HttpResponse<String>> pending = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> resp;
            try {
                resp = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                pending.cancel(true);
                throw new FlociException("No complete response from " + endpoint + path + " within "
                        + timeout.toMillis() + " ms. Is Floci running? Try 'floci status'.");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ConnectException) throw (ConnectException) e.getCause();
                throw new FlociException("Request failed: " + e.getCause().getMessage());
            }
            if (resp.statusCode() >= 400) {
                throw new FlociException("Server returned HTTP " + resp.statusCode() + " for " + path);
            }
            return MAPPER.readTree(resp.body());
        } catch (FlociException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlociException("Request interrupted.\nRe-run the command.");
        } catch (ConnectException e) {
            throw new FlociException("Connection refused at " + endpoint + ". Is Floci running? Try 'floci status' or 'floci start'.");
        } catch (Exception e) {
            throw new FlociException("Request failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String path, String body) throws FlociException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(url(path))
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
        } catch (ConnectException e) {
            throw new FlociException("Connection refused at " + endpoint + ". Is Floci running? Try 'floci start'.");
        } catch (Exception e) {
            throw new FlociException("Request failed: " + e.getMessage());
        }
    }

    private void deleteRequest(String path) throws FlociException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(url(path))
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

    private URI url(String path) {
        return URI.create(endpoint + path + query);
    }
}
