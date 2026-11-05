package io.floci.cli.unit;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.RestartCommand;
import io.floci.cli.commands.StartCommand;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RestartRecoveryTest {
    @TempDir Path profiles;

    static class FailedStart extends DockerClient {
        String state = "running";
        boolean failStop;
        final List<String> actions = new ArrayList<>();
        @Override public DockerHost daemonHost() { return new DockerHost(Kind.TCP, null, "tcp://host:2375"); }
        @Override public Optional<ContainerInfo> inspectContainer(String name) {
            return state == null ? Optional.empty() : Optional.of(new ContainerInfo("id", name, "floci/floci-gcp:0.9", state, "14588->4588/tcp"));
        }
        @Override public Optional<RunSettings> inspectRunSettings(String name, int port) {
            return Optional.of(new RunSettings("floci/floci-gcp:0.9", 14588, "/data/team's state",
                    Map.of("FLOCI_GCP_SERVICES", "storage,pubsub", "FLOCI_GCP_DOCKER_RESOURCE_NAMESPACE", "team"),
                    List.of()));
        }
        @Override public void stopContainer(String name, int timeout) throws DockerException {
            if (failStop) throw new DockerException("stop failed");
            actions.add("stop"); state = "exited";
        }
        @Override public void removeContainer(String name) { actions.add("remove"); state = null; }
        @Override public void pull(String image, String policy) { }
        @Override public void pull(String image, String policy, PrintStream stream) { }
        @Override public String startContainer(List<String> args) throws DockerException {
            actions.add("run"); throw new DockerException("run failed");
        }
    }

    @Test
    void failedStartReportsACopyableSnapshotInTextAndJson() throws Exception {
        for (String format : List.of("text", "json")) {
            FailedStart docker = new FailedStart();
            var restart = new RestartCommand(ProductProfile.GCP, docker);
            CommandLine command = new CommandLine(restart)
                    .setDefaultValueProvider(new ProfileDefaultValueProvider(new ProfileStore(profiles)));
            PrintStream out = System.out, err = System.err;
            ByteArrayOutputStream captured = new ByteArrayOutputStream(), stdout = new ByteArrayOutputStream();
            try {
                System.setErr(new PrintStream(captured)); System.setOut(new PrintStream(stdout));
                assertEquals(1, command.execute("--container", "floci-gcp-team", "-o", format));
            } finally { System.setErr(err); System.setOut(out); }
            assertEquals(List.of("stop", "remove", "run"), docker.actions);
            String line = captured.toString().lines().filter(s -> s.startsWith("floci gcp start")).findFirst().orElseThrow();
            assertTrue(line.contains("--port 14588"), line);
            assertTrue(line.contains("--image floci/floci-gcp:0.9"), line);
            assertTrue(line.contains("--persist '/data/team'\\''s state'"), line);
            assertTrue(line.contains("--services storage,pubsub --namespace team"), line);
            assertTrue(line.contains("--container floci-gcp-team"), line);
            assertFalse(line.contains("--endpoint"), line);
            if (format.equals("json")) assertEquals("", stdout.toString());
        }
    }

    @Test
    void aRunningContainerThatIsNotReadyGetsLogsAndWaitNotARefusedStart() throws Exception {
        FailedStart docker = new FailedStart() {
            @Override public String startContainer(List<String> args) {
                actions.add("run"); state = "running"; return "new-id";
            }
        };
        var restart = new RestartCommand(ProductProfile.GCP, docker) {
            @Override public StartCommand buildStartCommand() throws DockerException {
                StartCommand start = super.buildStartCommand();
                start.readinessTimeout("1ms");
                return start;
            }
        };
        CommandLine command = new CommandLine(restart)
                .setDefaultValueProvider(new ProfileDefaultValueProvider(new ProfileStore(profiles)));
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(stderr)); System.setOut(new PrintStream(new ByteArrayOutputStream()));
            assertEquals(1, command.execute("--container", "floci-gcp-team"));
        } finally { System.setErr(err); System.setOut(out); }
        String text = stderr.toString();
        assertTrue(text.contains("running but did not become ready"), text);
        assertTrue(text.contains("floci gcp logs --container floci-gcp-team"), text);
        assertTrue(text.contains("floci gcp wait --container floci-gcp-team"), text);
        assertFalse(text.contains("floci gcp start --container"), text);
    }

    private String restartStderr(ProductProfile product, FailedStart docker, String... args) throws Exception {
        var restart = new RestartCommand(product, docker) {
            @Override public StartCommand buildStartCommand() throws DockerException {
                StartCommand start = super.buildStartCommand();
                start.readinessTimeout("1ms");
                return start;
            }
        };
        CommandLine command = new CommandLine(restart)
                .setDefaultValueProvider(new ProfileDefaultValueProvider(new ProfileStore(profiles)));
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(stderr)); System.setOut(new PrintStream(new ByteArrayOutputStream()));
            assertEquals(1, command.execute(args));
        } finally { System.setErr(err); System.setOut(out); }
        return stderr.toString();
    }

    private static FailedStart comesUpRunning() {
        return new FailedStart() {
            @Override public String startContainer(List<String> args) {
                actions.add("run"); state = "running"; return "new-id";
            }
        };
    }

    @Test
    void awsRecoveryNamesItsTreeBecauseABareCommandFollowsTheDefaultProduct() throws Exception {
        String refused = restartStderr(ProductProfile.AWS, new FailedStart(), "--container", "other");
        assertTrue(refused.contains("floci aws start --container other"), refused);
        String unready = restartStderr(ProductProfile.AWS, comesUpRunning(), "--container", "other");
        assertTrue(unready.contains("floci aws logs --container other"), unready);
        assertTrue(unready.contains("floci aws wait --container other"), unready);
    }

    @Test
    void theSuggestedWaitChecksTheEndpointStartWaitedOn() throws Exception {
        String text = restartStderr(ProductProfile.GCP, comesUpRunning(),
                "--container", "floci-gcp-team", "--endpoint", "http://build-host:4588");
        assertTrue(text.contains("floci gcp wait --container floci-gcp-team --endpoint http://build-host:14588"), text);
    }

    @Test
    void theRecoveryCommandPrintsTheDirectoryALocalDaemonWouldMount() {
        FailedStart local = new FailedStart() {
            @Override public DockerHost daemonHost() { return new DockerHost(Kind.UNIX, null, "unix:///var/run/docker.sock"); }
        };
        StartCommand onLocal = new StartCommand(ProductProfile.GCP, local);
        new CommandLine(onLocal).parseArgs("--persist", "rel/data");
        assertTrue(onLocal.recoveryCommand().contains("--persist " + Path.of("rel/data").toAbsolutePath()), onLocal.recoveryCommand());

        StartCommand onRemote = new StartCommand(ProductProfile.GCP, new FailedStart());
        new CommandLine(onRemote).parseArgs("--persist", "/srv/data");
        assertTrue(onRemote.recoveryCommand().contains("--persist /srv/data"), onRemote.recoveryCommand());
    }

    @Test
    void failedStopDoesNotClaimTheOldContainerWasRemoved() {
        FailedStart docker = new FailedStart(); docker.failStop = true;
        PrintStream err = System.err; ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured));
            assertEquals(1, new CommandLine(new RestartCommand(ProductProfile.GCP, docker)).execute());
        } finally { System.setErr(err); }
        assertFalse(captured.toString().contains("old container was removed"));
        assertTrue(docker.actions.isEmpty());
    }
}
