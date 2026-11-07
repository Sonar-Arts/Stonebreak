package com.stonebreak.world.save.io;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The one way save files reach disk: write a uniquely named sibling temp file, then rename it
 * over the target. Readers see either the old file or the new one, never a torn write.
 */
public final class AtomicFiles {

    /** Tries for about a second in all before a write fails. */
    private static final int REPLACE_ATTEMPTS = 20;

    private AtomicFiles() {
    }

    public static void write(Path target, byte[] payload) throws IOException {
        // Unique temp name per write — a fixed sibling name races when two threads write the
        // same target concurrently (the loser's move throws NoSuchFileException after the winner
        // consumed the shared temp file).
        Path temp = target.resolveSibling(target.getFileName() + "."
            + Long.toUnsignedString(System.nanoTime(), 36)
            + "-" + Thread.currentThread().threadId() + ".tmp");
        Path parent = target.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Files.write(temp, payload);
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    replace(temp, target);
                    return;
                } catch (AccessDeniedException e) {
                    // Windows refuses to rename over a file that another thread is reading or
                    // renaming over at that moment (a load of the same file, a concurrent write
                    // of it). Both are brief, so wait them out — then fail loudly.
                    if (attempt >= REPLACE_ATTEMPTS) {
                        throw e;
                    }
                    sleepBeforeRetry(attempt);
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private static void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void sleepBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(Math.min(attempt * 5L, 100L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while retrying a save");
        }
    }
}
