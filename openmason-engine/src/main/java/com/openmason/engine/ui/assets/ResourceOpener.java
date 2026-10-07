package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.ArchiveLimits;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.io.ArchiveIO;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a resource by portable relative path. Packaged resources must be opened by the module
 * that owns them (JPMS resource encapsulation), so the game and tool pass their own opener
 * ({@code path -> Main.class.getResourceAsStream("/" + path)}) instead of the engine guessing.
 */
@FunctionalInterface
public interface ResourceOpener {

    /** @return the bytes, or {@code null} when the resource does not exist */
    UiBytes read(String path) throws IOException;

    /** A stream-returning opener such as {@code Class::getResourceAsStream}; {@code null} = absent. */
    @FunctionalInterface
    interface StreamOpener {
        InputStream open(String path) throws IOException;
    }

    static ResourceOpener streams(StreamOpener opener) {
        return path -> {
            try (InputStream in = opener.open(path)) {
                return in == null ? null : ProjectFolder.readBounded(in, path);
            }
        };
    }

    /** Files under {@code root}; unsafe or escaping paths read as absent. */
    static ResourceOpener directory(Path root) {
        ProjectFolder folder = new ProjectFolder(root);
        return path -> folder.exists(path) ? folder.read(path) : null;
    }

    /**
     * A ZIP resource pack, read once into memory under the archive limits. Entries follow the
     * archive naming rules ({@link ArchiveIO}), so a pack behaves identically on every OS.
     */
    static ResourceOpener zip(Path archive) throws IOException {
        UiDiagnostics d = new UiDiagnostics();
        Map<String, byte[]> entries = ArchiveIO.read(Files.readAllBytes(archive), ArchiveLimits.DEFAULT, d);
        if (d.hasErrors()) {
            throw new IOException("Resource pack " + archive.getFileName() + " is invalid: " + d.list());
        }
        Map<String, UiBytes> bytes = new HashMap<>();
        entries.forEach((k, v) -> bytes.put(k, UiBytes.copyOf(v)));
        return bytes::get;
    }
}
