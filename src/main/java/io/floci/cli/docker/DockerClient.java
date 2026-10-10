package io.floci.cli.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Thin subprocess wrapper around the docker CLI.
 * Subprocess invocation avoids reflection config needed by docker-java on native image.
 */
public class DockerClient {

    /** How long a non-streaming docker call may take before it is killed. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String binary;
    private final Duration timeout;

    public DockerClient() {
        this("docker");
    }

    /** Test seam: run {@code binary} in place of {@code docker}. */
    public DockerClient(String binary) {
        this(binary, DEFAULT_TIMEOUT);
    }

    /** Test seam: run {@code binary}, killing any non-streaming call that outlives {@code timeout}. */
    public DockerClient(String binary, Duration timeout) {
        this.binary = binary;
        this.timeout = timeout;
    }

    public record ContainerInfo(
            String id,
            String name,
            String image,
            String state,
            String ports) {}

    public record ImageInfo(String id, String repository, String tag, String digest) {}

    /**
     * What a container was started with, as {@code restart} needs it back: the image, the host
     * port bound to {@code containerPort}, the host directory bind-mounted at {@code /app/data}
     * (null for a docker volume), and the environment. Any of them may be absent (null, or an empty map).
     */
    public record RunSettings(String image, Integer hostPort, String persistSource, Map<String, String> env) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public Optional<RunSettings> inspectRunSettings(String name, int containerPort) throws DockerException {
        String out;
        try {
            out = run("docker", "container", "inspect", "--format", "{{json .}}", "--", name);
        } catch (DockerException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("no such")) {
                return Optional.empty();
            }
            throw e;
        }
        if (out.isBlank()) return Optional.empty();
        try {
            JsonNode c = JSON.readTree(out);
            String image = textOrNull(c.path("Config").path("Image"));
            Integer hostPort = null;
            JsonNode binding = c.path("HostConfig").path("PortBindings").path(containerPort + "/tcp");
            if (binding.isArray() && !binding.isEmpty()) {
                String text = binding.get(0).path("HostPort").asText("");
                if (!text.isBlank()) hostPort = Integer.valueOf(text);
            }
            String persist = null;
            for (JsonNode mount : c.path("Mounts")) {
                // Only a host directory is a --persist. The images declare /app/data as a volume,
                // so without --persist docker mounts an anonymous volume there; its Source is a
                // path inside the docker VM, not something start can mount again.
                if ("/app/data".equals(mount.path("Destination").asText())
                        && "bind".equals(mount.path("Type").asText())) {
                    persist = textOrNull(mount.path("Source"));
                }
            }
            Map<String, String> env = new LinkedHashMap<>();
            for (JsonNode entry : c.path("Config").path("Env")) {
                String kv = entry.asText();
                int eq = kv.indexOf('=');
                if (eq > 0) env.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
            return Optional.of(new RunSettings(image, hostPort, persist, Map.copyOf(env)));
        } catch (Exception e) {
            throw new DockerException("Could not read the settings of container '" + name + "': " + e.getMessage());
        }
    }

    private static String textOrNull(JsonNode node) {
        String text = node.asText("");
        return text.isBlank() ? null : text;
    }

    public String dockerVersion() throws DockerException {
        return run("docker", "version", "--format", "{{.Server.Version}}").trim();
    }

    public boolean isDaemonReachable() {
        try {
            // Probe connectivity by exit code only. `docker info` / `podman info`
            // exit 0 when the daemon (or DOCKER_HOST socket) is reachable. Avoid a
            // Docker-only template field like {{.ServerVersion}}, which errors under
            // Podman and yields a false "daemon not reachable". Going through run() gives
            // the probe the same time limit and cleanup as every other call, so a daemon
            // that accepts the connection and then stalls reads as unreachable.
            run("docker", "info");
            return true;
        } catch (DockerException e) {
            return false;
        }
    }

    public Optional<ContainerInfo> inspectContainer(String name) throws DockerException {
        try {
            String out = run("docker", "container", "inspect",
                    "--format",
                    "{{.Id}}|{{.Name}}|{{.Config.Image}}|{{.State.Status}}|" +
                            "{{range $p, $b := .NetworkSettings.Ports}}{{if $b}}{{(index $b 0).HostPort}}->{{$p}} {{end}}{{end}}",
                    "--", name);
            if (out.isBlank()) return Optional.empty();
            String[] parts = out.trim().split("\\|", -1);
            return Optional.of(new ContainerInfo(
                    parts.length > 0 ? parts[0] : "",                       // id
                    parts.length > 1 ? parts[1].replaceFirst("^/", "") : name,
                    parts.length > 2 ? parts[2] : "",                       // image
                    parts.length > 3 ? parts[3] : "",                       // status
                    parts.length > 4 ? parts[4].trim() : ""));              // ports
        } catch (DockerException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("no such")) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public boolean isImagePresent(String image) throws DockerException {
        try {
            String out = run("docker", "images", "-q", "--", image);
            return !out.isBlank();
        } catch (DockerException e) {
            return false;
        }
    }

    public Optional<String> imageDigest(String image) throws DockerException {
        try {
            String out = run("docker", "inspect", "--format", "{{index .RepoDigests 0}}", "--", image);
            return out.isBlank() ? Optional.empty() : Optional.of(out.trim());
        } catch (DockerException e) {
            return Optional.empty();
        }
    }

    /** The value of label {@code key} on a local image, empty when the label or image is missing. */
    public Optional<String> imageLabel(String image, String key) throws DockerException {
        try {
            String out = run("docker", "inspect", "--format",
                    "{{index .Config.Labels \"" + key + "\"}}", "--", image);
            String value = out.trim();
            return value.isEmpty() || "<no value>".equals(value) ? Optional.empty() : Optional.of(value);
        } catch (DockerException e) {
            return Optional.empty();
        }
    }

    public void pull(String image, String policy) throws DockerException {
        if ("never".equalsIgnoreCase(policy)) return;
        if ("missing".equalsIgnoreCase(policy) && isImagePresent(image)) return;
        runStreaming("docker", "pull", "--", image);
    }

    public String startContainer(List<String> args) throws DockerException {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.addAll(args);
        // docker run may still pull the image (--pull never with a missing image), so it gets
        // far longer than the lookups.
        return run(Duration.ofMinutes(10), cmd.toArray(String[]::new)).trim();
    }

    /**
     * Stops {@code name}, giving it {@code timeout} seconds before docker kills it. A negative
     * {@code timeout} is docker's "wait indefinitely", so that call gets no time limit either.
     */
    public void stopContainer(String name, int timeout) throws DockerException {
        // -t is the one spelling every docker and podman accepts (--time is deprecated for --timeout).
        run(stopLimit(timeout), "docker", "stop", "-t", String.valueOf(timeout), "--", name);
    }

    // The container's grace period plus this client's own limit for docker to answer; null (no
    // limit) when the grace period is itself unlimited.
    private Duration stopLimit(int timeout) {
        return timeout < 0 ? null : Duration.ofSeconds(timeout).plus(this.timeout);
    }

    public void removeContainer(String name) throws DockerException {
        run("docker", "rm", "--", name);
    }

    public void streamLogs(String name, boolean follow, int tail, String since) throws DockerException {
        List<String> cmd = new ArrayList<>(List.of("docker", "logs"));
        if (follow) cmd.add("--follow");
        if (tail > 0) { cmd.add("--tail"); cmd.add(String.valueOf(tail)); }
        if (since != null && !since.isBlank()) { cmd.add("--since"); cmd.add(since); }
        cmd.add("--");
        cmd.add(name);
        runStreaming(cmd.toArray(String[]::new));
    }

    private String run(String... cmd) throws DockerException {
        return run(timeout, cmd);
    }

    /** Runs {@code cmd} for at most {@code limit}; a null {@code limit} waits as long as it takes. */
    private String run(Duration limit, String... cmd) throws DockerException {
        Process proc = null;
        boolean finished = false;
        try {
            proc = runProcess(cmd);
            // Both streams drain at once, so a full stderr pipe cannot stall the process; the wait
            // is bounded and interruptible, and any way out of it but a normal exit kills the
            // process, so no docker call outlives the CLI.
            CompletableFuture<String> stdout = drain(proc.getInputStream());
            CompletableFuture<String> stderr = drain(proc.getErrorStream());
            if (limit == null) {
                proc.waitFor();
            } else if (!proc.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new DockerException("'" + describe(cmd) + "' did not finish within " + limit.toSeconds()
                        + "s.\nCheck that the Docker daemon is responsive ('docker info') and re-run the command.");
            }
            finished = true;
            String out = stdout.join();
            String err = stderr.join();
            if (proc.exitValue() != 0) {
                throw new DockerException(err.isBlank() ? out : err);
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerException("Interrupted");
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("No such file")) {
                throw new DockerException("docker binary not found in PATH. Install Docker Desktop or Docker Engine.");
            }
            throw new DockerException(e.getMessage());
        } finally {
            if (proc != null && !finished) proc.destroyForcibly();
        }
    }

    // "docker container inspect" for messages: the subcommand words, never the user's arguments.
    private static String describe(String... cmd) {
        StringBuilder words = new StringBuilder();
        for (String part : cmd) {
            if (part.startsWith("-")) break;
            if (!words.isEmpty()) words.append(' ');
            words.append(part);
        }
        return words.toString();
    }

    private void runStreaming(String... cmd) throws DockerException {
        Process proc = null;
        boolean finished = false;
        try {
            Process started = runProcess(cmd);
            proc = started;
            Thread out = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(started.getInputStream()))) {
                    reader.lines().forEach(System.out::println);
                } catch (IOException ignored) {}
            });
            Thread err = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(started.getErrorStream()))) {
                    reader.lines().forEach(System.err::println);
                } catch (IOException ignored) {}
            });
            out.start();
            err.start();
            int code = proc.waitFor();
            finished = true;
            out.join();
            err.join();
            if (code != 0) {
                throw new DockerException("docker command failed with exit code " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerException("Interrupted");
        } catch (IOException e) {
            throw new DockerException(e.getMessage());
        } finally {
            // Streaming calls (pull, logs --follow) have no time limit, but never outlive the CLI.
            if (proc != null && !finished) proc.destroyForcibly();
        }
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        CompletableFuture<String> text = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (stream) {
                text.complete(new String(stream.readAllBytes()));
            } catch (IOException e) {
                text.complete("");
            }
        });
        return text;
    }

    private Process runProcess(String... cmd) throws IOException {
        if (cmd.length > 0 && "docker".equals(cmd[0])) {
            cmd = cmd.clone();
            cmd[0] = binary;
        }
        return new ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start();
    }

    public static boolean isInstalled() {
        try {
            Process p = new ProcessBuilder("docker", "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (p.waitFor(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                return p.exitValue() == 0;
            }
            p.destroyForcibly();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** How the Docker daemon is reached, resolved from the environment. */
    public enum Kind { UNIX, TCP, NPIPE }

    /**
     * Parsed Docker daemon endpoint. {@code socketPath} is the local socket/pipe path
     * for {@link Kind#UNIX}/{@link Kind#NPIPE}, and null for {@link Kind#TCP}.
     */
    public record DockerHost(Kind kind, String socketPath, String raw) {}

    private static final String DEFAULT_UNIX_SOCKET = "/var/run/docker.sock";
    private static final String DEFAULT_WINDOWS_PIPE = "\\\\.\\pipe\\docker_engine";

    /** Resolve the Docker endpoint from the current process environment and OS. */
    public static DockerHost dockerHost() {
        return parseDockerHost(
                System.getenv("DOCKER_HOST"),
                System.getenv("DOCKER_SOCK"),
                System.getProperty("os.name", ""));
    }

    /**
     * Pure resolver for the Docker endpoint. Precedence: {@code DOCKER_HOST}
     * (standard) → {@code DOCKER_SOCK} (legacy override) → OS default.
     */
    public static DockerHost parseDockerHost(String dockerHostEnv, String dockerSockEnv, String osName) {
        boolean windows = osName != null && osName.toLowerCase().contains("win");

        if (dockerHostEnv != null && !dockerHostEnv.isBlank()) {
            String value = dockerHostEnv.trim();
            if (value.startsWith("unix://")) {
                return new DockerHost(Kind.UNIX, value.substring("unix://".length()), value);
            }
            if (value.startsWith("tcp://") || value.startsWith("http://") || value.startsWith("https://")) {
                return new DockerHost(Kind.TCP, null, value);
            }
            if (value.startsWith("npipe://")) {
                return new DockerHost(Kind.NPIPE, value.substring("npipe://".length()), value);
            }
            // No scheme — treat as a bare socket/pipe path.
            return new DockerHost(windows ? Kind.NPIPE : Kind.UNIX, value, value);
        }

        if (dockerSockEnv != null && !dockerSockEnv.isBlank()) {
            return new DockerHost(Kind.UNIX, dockerSockEnv.trim(), dockerSockEnv.trim());
        }

        if (windows) {
            return new DockerHost(Kind.NPIPE, DEFAULT_WINDOWS_PIPE, null);
        }
        return new DockerHost(Kind.UNIX, DEFAULT_UNIX_SOCKET, null);
    }

    /** Back-compat accessor for the resolved local socket/pipe path (null for TCP). */
    public static String socketPath() {
        return dockerHost().socketPath();
    }

    /**
     * The {@code docker run} arguments needed to give a container access to the host
     * Docker daemon. Unix sockets are bind-mounted at the canonical in-container path;
     * remote TCP daemons are passed through via {@code DOCKER_HOST}.
     */
    public static List<String> dockerSocketRunArgs() {
        DockerHost host = dockerHost();
        return switch (host.kind()) {
            case TCP -> List.of("-e", "DOCKER_HOST=" + host.raw());
            case UNIX -> List.of("-v", host.socketPath() + ":" + DEFAULT_UNIX_SOCKET);
            case NPIPE -> List.of("-v", DEFAULT_UNIX_SOCKET + ":" + DEFAULT_UNIX_SOCKET);
        };
    }
}
