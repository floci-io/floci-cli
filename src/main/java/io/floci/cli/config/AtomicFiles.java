package io.floci.cli.config;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Replaces a file all at once: the bytes go to a temporary file in the same directory, which is
 * then moved over the target. A crash or Ctrl-C mid-write leaves the old file intact rather than a
 * truncated one.
 */
final class AtomicFiles {

    private AtomicFiles() {}

    static void write(Path target, byte[] bytes) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        // A short fixed prefix: one built from the target's name would push a name already near
        // the file system's length limit over it.
        Path temp = Files.createTempFile(dir, ".floci-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp); // only still there if the move failed
        }
    }
}
