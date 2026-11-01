package com.openmason.engine.format.omui.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

/**
 * Whole-file replacement that never leaves a half-written target: bytes go to a temporary
 * sibling, are forced to disk, then renamed over the target in one step. A failure at any
 * point leaves the original untouched and removes the temporary.
 *
 * <p>Saving through a symbolic link replaces the file the link points to (the link stays a
 * link). An existing file keeps its POSIX permissions. The directory is flushed after the
 * rename where the platform allows it.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void write(Path target, byte[] bytes) throws IOException {
        Path absolute = target.toAbsolutePath();
        if (Files.isSymbolicLink(absolute)) {
            absolute = absolute.toRealPath();
        }
        Path dir = absolute.getParent();
        Files.createDirectories(dir);
        Set<PosixFilePermission> permissions = existingPermissions(absolute);
        // createFile (not createTempFile, which forces 0600) so new files get the umask default.
        Path temp = Files.createFile(dir.resolve("." + absolute.getFileName() + "." + UUID.randomUUID() + ".tmp"));
        try {
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            if (permissions != null) {
                Files.setPosixFilePermissions(temp, permissions);
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Filesystems without atomic rename (some network mounts): still never partial,
                // since the temporary was fully written and flushed first.
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory(dir);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Set<PosixFilePermission> existingPermissions(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                || Files.getFileAttributeView(file, PosixFileAttributeView.class) == null) {
            return null;
        }
        return Files.getPosixFilePermissions(file);
    }

    private static void forceDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Not supported on every platform (e.g. Windows); the file itself is already flushed.
        }
    }
}
