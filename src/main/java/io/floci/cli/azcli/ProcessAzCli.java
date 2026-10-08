package io.floci.cli.azcli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** {@link AzCli} backed by a real {@code az} subprocess. */
public class ProcessAzCli implements AzCli {

    // az login can take a while on a cold start; anything past this is a hang, not slowness.
    private static final long TIMEOUT_SECONDS = 120;

    // On Windows az is a batch file, which ProcessBuilder only resolves by its full name.
    private static final String EXECUTABLE =
            System.getProperty("os.name", "").toLowerCase().startsWith("windows") ? "az.cmd" : "az";

    @Override
    public Result run(List<String> args, Map<String, String> env) throws AzCliNotFoundException {
        List<String> command = new ArrayList<>();
        command.add(EXECUTABLE);
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        env.forEach((key, value) -> {
            if (value == null) pb.environment().remove(key);
            else pb.environment().put(key, value);
        });

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new AzCliNotFoundException("az CLI not found in PATH (" + e.getMessage() + ")");
        }
        closeQuietly(process);
        // Drain on another thread: az can print more than the pipe buffer holds, and an
        // undrained pipe would block it forever.
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(124, "az " + String.join(" ", args.subList(0, Math.min(2, args.size())))
                        + " timed out after " + TIMEOUT_SECONDS + "s");
            }
            return new Result(process.exitValue(), output.join());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(130, "interrupted");
        }
    }

    // az never reads stdin here; closing it means a prompt fails fast instead of hanging.
    private static void closeQuietly(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
