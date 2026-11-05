package io.floci.cli.unit;

import io.floci.cli.ProductProfile;
import io.floci.cli.commands.DoctorCommand;
import io.floci.cli.commands.EnvCommand;
import io.floci.cli.commands.LogsCommand;
import io.floci.cli.commands.ServicesCommand;
import io.floci.cli.commands.StartCommand;
import io.floci.cli.commands.StatusCommand;
import io.floci.cli.commands.StopCommand;
import io.floci.cli.commands.WaitCommand;
import io.floci.cli.commands.az.AzEnvCommand;
import io.floci.cli.commands.gcp.GcpEnvCommand;
import io.floci.cli.commands.oci.OciEnvCommand;
import io.floci.cli.config.ProfileDefaultValueProvider;
import io.floci.cli.config.ProfileStore;
import io.floci.cli.docker.DockerClient;
import io.floci.cli.docker.DockerException;
import io.floci.cli.doctor.checks.ContainerRunningCheck;
import io.floci.cli.doctor.checks.ImagePresentCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

class InstanceHintsTest {
    @TempDir Path temp;
    private static final List<ProductProfile> PRODUCTS = List.of(ProductProfile.AWS, ProductProfile.GCP, ProductProfile.AZ, ProductProfile.OCI);
    static class FakeDocker extends DockerClient {
        boolean running;
        @Override public DockerHost daemonHost() { return new DockerHost(Kind.TCP, null, "tcp://host:2375"); }
        @Override public Optional<ContainerInfo> inspectContainer(String name) {
            return running ? Optional.of(new ContainerInfo("id", name, "image", "running", "")) : Optional.empty();
        }
        @Override public boolean isImagePresent(String image) { return false; }
    }
    private record Run(int exit, String out, String err) { }
    private Run run(Callable<Integer> command, String... args) throws Exception {
        Files.writeString(temp.resolve("team alpha.yaml"), "container: other\nendpoint: http://127.0.0.1:1\n");
        CommandLine cli = new CommandLine(command).setDefaultValueProvider(new ProfileDefaultValueProvider(new ProfileStore(temp)));
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream(), stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout)); System.setErr(new PrintStream(stderr));
            int exit = cli.execute(args);
            return new Run(exit, stdout.toString(), stderr.toString());
        } finally { System.setOut(out); System.setErr(err); }
    }
    @Test
    void lifecycleFailureHintsSelectTheSameProfileAndContainerInEveryTree() throws Exception {
        for (ProductProfile product : PRODUCTS) {
            FakeDocker docker = new FakeDocker();
            for (Callable<Integer> command : List.<Callable<Integer>>of(new StopCommand(product, docker), new LogsCommand(product, docker),
                    new ServicesCommand(product, docker), new WaitCommand(product, docker))) {
                String[] args = command instanceof WaitCommand
                        ? new String[]{"--profile", "team alpha", "--container", "override", "--timeout", "1ms"}
                        : new String[]{"--profile", "team alpha", "--container", "override"};
                Run result = run(command, args);
                assertEquals(1, result.exit());
                assertTrue(result.err().contains("--profile 'team alpha' --container override"), result.err());
            }
            docker.running = true;
            Run start = run(new StartCommand(product, docker), "--profile", "team alpha", "--container", "override");
            assertTrue(start.err().contains(product.commandPrefix() + " stop --profile 'team alpha' --container override"), start.err());
            docker.running = false;
            Run status = run(new StatusCommand(product, docker), "--profile", "team alpha");
            assertTrue(status.out().contains("start --profile 'team alpha'"), status.out());
        }
    }
    @Test
    void aFailedPullKeepsTheWholeStartInvocation() throws Exception {
        FakeDocker docker = new FakeDocker() {
            @Override public void pull(String image, String policy) throws DockerException { throw new DockerException("no such image"); }
            @Override public void pull(String image, String policy, PrintStream stream) throws DockerException { throw new DockerException("no such image"); }
        };
        Run start = run(new StartCommand(ProductProfile.AWS, docker), "--container", "other", "--port", "14566",
                "--image", "my image:1", "--pull", "always", "--security-opt", "label=disable",
                "--security-opt", "seccomp=/etc/my profile.json");
        assertEquals(1, start.exit());
        assertTrue(start.err().contains("floci start --pull never --container other --port 14566 --image 'my image:1'"
                + " --security-opt label=disable --security-opt 'seccomp=/etc/my profile.json'"), start.err());
    }
    @Test
    void envLoadHintsPreserveTheProfileInAllFourTrees() throws Exception {
        FakeDocker docker = new FakeDocker();
        for (Callable<Integer> env : List.<Callable<Integer>>of(new EnvCommand(docker), new GcpEnvCommand(docker),
                new OciEnvCommand(docker), new AzEnvCommand(temp, endpoint -> { throw new AssertionError("no cert expected"); }, docker))) {
            Run result = run(env, "--profile", "team alpha");
            assertEquals(0, result.exit(), result.err());
            assertTrue(result.out().contains("env --profile 'team alpha' -o text"), result.out());
        }
    }
    @Test
    void envLoadHintsQuoteForTheShellTheyAreFor() throws Exception {
        Files.writeString(temp.resolve("team's.yaml"), "container: other\nendpoint: http://127.0.0.1:1\n");
        for (Callable<Integer> env : List.<Callable<Integer>>of(new EnvCommand(new FakeDocker()), new GcpEnvCommand(new FakeDocker()),
                new OciEnvCommand(new FakeDocker()), new AzEnvCommand(temp, endpoint -> { throw new AssertionError("no cert expected"); }, new FakeDocker()))) {
            assertTrue(run(env, "--profile", "team's").out().contains("--profile 'team'\\''s' -o text)\""));
        }
        for (Callable<Integer> env : List.<Callable<Integer>>of(new EnvCommand(new FakeDocker()), new GcpEnvCommand(new FakeDocker()),
                new OciEnvCommand(new FakeDocker()), new AzEnvCommand(temp, endpoint -> { throw new AssertionError("no cert expected"); }, new FakeDocker()))) {
            String out = run(env, "--profile", "team's", "--shell", "powershell").out();
            assertTrue(out.contains("--profile 'team''s' -o text --shell powershell | Invoke-Expression"), out);
        }
        for (Callable<Integer> env : List.<Callable<Integer>>of(new EnvCommand(new FakeDocker()), new GcpEnvCommand(new FakeDocker()),
                new OciEnvCommand(new FakeDocker()), new AzEnvCommand(temp, endpoint -> { throw new AssertionError("no cert expected"); }, new FakeDocker()))) {
            String out = run(env, "--profile", "team's", "--shell", "fish").out();
            assertTrue(out.contains("--profile 'team\\'s' -o text --shell fish | source"), out);
        }
    }
    private List<Callable<Integer>> allEnvCommands() {
        return List.of(new EnvCommand(new FakeDocker()), new GcpEnvCommand(new FakeDocker()), new OciEnvCommand(new FakeDocker()),
                new AzEnvCommand(temp, endpoint -> { throw new AssertionError("no cert expected"); }, new FakeDocker()));
    }
    @Test
    void aValueWithALineBreakCannotEscapeTheHintComment() throws Exception {
        for (String breaker : List.of("\n", "\r", "\u0085", "\u2028", "\u2029")) {
            for (String shell : List.of("bash", "fish", "powershell")) {
                for (Callable<Integer> env : allEnvCommands()) {
                    Run result = run(env, "--container", "x" + breaker + "touch injected" + breaker + "#",
                            "--endpoint", "http://localhost:1", "--shell", shell);
                    assertEquals(0, result.exit(), result.err());
                    assertFalse(result.out().contains("touch injected"), result.out());
                    assertTrue(result.out().contains("--container <value>"), result.out());
                }
            }
        }
    }
    @Test
    void nonPosixShellsQuoteEvenAPlainValue() throws Exception {
        for (Callable<Integer> env : allEnvCommands()) {
            String out = run(env, "--container", "@team", "--endpoint", "http://localhost:1", "--shell", "powershell").out();
            assertTrue(out.contains("--container '@team'"), out);
        }
        for (Callable<Integer> env : allEnvCommands()) {
            String out = run(env, "--container", "@team", "--endpoint", "http://localhost:1", "--shell", "fish").out();
            assertTrue(out.contains("--container '@team'"), out);
        }
        for (Callable<Integer> env : allEnvCommands()) {
            String out = run(env, "--container", "@team", "--endpoint", "http://localhost:1").out();
            assertTrue(out.contains("--container @team"), out);
        }
    }
    @Test
    void aProfileThatAsksForJsonStillGetsALoadableHint() throws Exception {
        Files.writeString(temp.resolve("json.yaml"), "container: other\nendpoint: http://127.0.0.1:1\noutput: json\n");
        String out = run(new EnvCommand(new FakeDocker()), "--profile", "json", "-o", "text").out();
        assertTrue(out.contains("eval \"$(floci env --profile json -o text)\""), out);
    }
    @Test
    void aLoadHintWithoutAProfileStaysBare() throws Exception {
        assertTrue(run(new EnvCommand(new FakeDocker())).out().contains("eval \"$(floci env)\""));
    }
    @Test
    void doctorPassesTheSelectorToCheckFixesInStructuredOutput() throws Exception {
        for (ProductProfile product : PRODUCTS) {
            FakeDocker docker = new FakeDocker();
            DoctorCommand doctor = new DoctorCommand(product, docker, List.of(new ContainerRunningCheck(docker, product), new ImagePresentCheck(product, docker)));
            Run result = run(doctor, "--profile", "team alpha", "--container", "override", "--json");
            assertEquals(1, result.exit());
            assertEquals(2, result.out().split("--profile 'team alpha' --container override", -1).length - 1, result.out());
        }
    }
}
