package io.floci.cli.docker;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link DockerClient} that answers each read-only query once per instance: the first caller
 * runs the docker subprocess and every later or concurrent caller gets the same answer (or the
 * same failure). Meant for one diagnostic run, where several checks ask the same questions; never
 * hold one across a command that changes container or image state.
 */
public class CachingDockerClient extends DockerClient {

    private final DockerClient delegate;

    private final ConcurrentHashMap<String, Memo<?>> memos = new ConcurrentHashMap<>();

    public CachingDockerClient() {
        this(new DockerClient());
    }

    /** {@code delegate} answers the first call for each query; a test seam for counting. */
    public CachingDockerClient(DockerClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public String dockerVersion() throws DockerException {
        return memo("version", delegate::dockerVersion);
    }

    @Override
    public boolean isDaemonReachable() {
        try {
            return memo("daemon", delegate::isDaemonReachable);
        } catch (DockerException e) {
            return false;
        }
    }

    @Override
    public Optional<ContainerInfo> inspectContainer(String name) throws DockerException {
        return memo("container:" + name, () -> delegate.inspectContainer(name));
    }

    @Override
    public boolean isImagePresent(String image) throws DockerException {
        return memo("image:" + image, () -> delegate.isImagePresent(image));
    }

    @Override
    public Optional<String> imageLabel(String image, String key) throws DockerException {
        return memo("label:" + image + ":" + key, () -> delegate.imageLabel(image, key));
    }

    @SuppressWarnings("unchecked")
    private <T> T memo(String key, DockerCall<T> call) throws DockerException {
        return ((Memo<T>) memos.computeIfAbsent(key, k -> new Memo<>())).get(call);
    }

    @FunctionalInterface
    private interface DockerCall<T> {
        T call() throws DockerException;
    }

    // One answer per key, computed by the first caller while concurrent callers wait on the lock.
    private static final class Memo<T> {
        private boolean done;
        private T value;
        private DockerException failure;

        synchronized T get(DockerCall<T> call) throws DockerException {
            if (!done) {
                try {
                    value = call.call();
                } catch (DockerException e) {
                    failure = e;
                }
                done = true;
            }
            if (failure != null) throw failure;
            return value;
        }
    }
}
