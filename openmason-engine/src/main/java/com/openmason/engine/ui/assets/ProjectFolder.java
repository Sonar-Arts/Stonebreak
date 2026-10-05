package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.ArchiveLimits;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.io.AtomicFiles;
import com.openmason.engine.format.omui.io.EntryPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * File access under one root by portable relative path ({@code textures/ui/panel.sbt}). Paths
 * follow the archive entry rules, so whatever a document records as a {@code sourceHint} can
 * be read here on any OS, and nothing can escape the root. Writes are atomic.
 */
public final class ProjectFolder {

    private final Path root;

    public ProjectFolder(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** @throws IOException when {@code relative} is not a safe portable path */
    public Path resolve(String relative) throws IOException {
        String problem = EntryPaths.problem(relative);
        if (problem != null) {
            throw new IOException("Unsafe project path '" + relative + "': " + problem);
        }
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) {
            throw new IOException("Project path '" + relative + "' escapes the project");
        }
        return p;
    }

    /** True when {@code relative} is a safe path naming an existing regular file. */
    public boolean exists(String relative) {
        try {
            return Files.isRegularFile(resolve(relative));
        } catch (IOException e) {
            return false;
        }
    }

    /** @return the file's bytes, or {@code null} when it does not exist */
    public UiBytes read(String relative) throws IOException {
        Path p = resolve(relative);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(p)) {
            return readBounded(in, relative);
        }
    }

    public void write(String relative, UiBytes bytes) throws IOException {
        Path p = resolve(relative);
        Files.createDirectories(p.getParent());
        AtomicFiles.write(p, bytes.toArray());
    }

    public void delete(String relative) throws IOException {
        Files.deleteIfExists(resolve(relative));
    }

    /** {@code file} as a portable relative path, or {@code null} when it is outside the root. */
    public String relativize(Path file) {
        Path p = file.toAbsolutePath().normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            return null;
        }
        String rel = root.relativize(p).toString().replace('\\', '/');
        return EntryPaths.problem(rel) == null ? rel : null;
    }

    /** Reads at most the per-entry archive limit, so one huge file cannot exhaust memory. */
    static UiBytes readBounded(InputStream in, String what) throws IOException {
        long limit = ArchiveLimits.DEFAULT.maxEntryBytes();
        byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limit + 1));
        if (data.length > limit) {
            throw new IOException("'" + what + "' exceeds " + limit + " bytes");
        }
        return UiBytes.copyOf(data);
    }
}
