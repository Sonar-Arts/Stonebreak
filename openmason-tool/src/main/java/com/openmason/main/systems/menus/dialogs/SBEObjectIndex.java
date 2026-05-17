package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbe.SBEParser;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Cached index of every SBE entity reachable from the bundled Stonebreak
 * resources. The SBE counterpart to {@link SBOObjectIndex}: it enumerates the
 * {@code objectId}s already in use so authoring UIs (SBE exporter, SBE editor)
 * can show them and help avoid collisions.
 *
 * <p>Loaded lazily on first use, refreshable on demand.
 */
public final class SBEObjectIndex {

    private static volatile List<Entry> cached;

    private SBEObjectIndex() {}

    /** One registered SBE entity. */
    public record Entry(String objectId, String objectName, String entityType) {}

    public static List<Entry> listAll() {
        List<Entry> snapshot = cached;
        if (snapshot == null) {
            synchronized (SBEObjectIndex.class) {
                if (cached == null) {
                    cached = scan();
                }
                snapshot = cached;
            }
        }
        return snapshot;
    }

    public static synchronized void refresh() {
        cached = scan();
    }

    private static List<Entry> scan() {
        List<Entry> out = new ArrayList<>();
        SBEParser parser = new SBEParser();
        for (Path p : discover("sbe/Mobs")) {
            try {
                SBEParser.RawParse raw = parser.parseRaw(p);
                out.add(new Entry(
                        raw.manifest().objectId(),
                        raw.manifest().objectName(),
                        raw.manifest().entityType()
                ));
            } catch (IOException ignored) {
                // best-effort enumeration; skip unreadable files
            }
        }
        out.sort((a, b) -> a.objectId().compareToIgnoreCase(b.objectId()));
        return Collections.unmodifiableList(out);
    }

    /**
     * Locate every {@code .sbe} file reachable under the given resource
     * subdirectory (e.g. {@code "sbe/Mobs"}). Prefers the dev filesystem so
     * freshly-exported files are seen without rebuilding, falls back to the
     * classpath.
     */
    private static List<Path> discover(String resourceDir) {
        List<Path> paths = new ArrayList<>();

        Path[] candidates = {
                Path.of(resourceDir),
                Path.of("stonebreak-game/src/main/resources").resolve(resourceDir),
                Path.of("../stonebreak-game/src/main/resources").resolve(resourceDir)
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                try (Stream<Path> stream = Files.list(candidate)) {
                    stream.filter(p -> p.toString().toLowerCase().endsWith(".sbe")).forEach(paths::add);
                } catch (IOException ignored) {}
                if (!paths.isEmpty()) return paths;
            }
        }

        try {
            ClassLoader cl = SBEObjectIndex.class.getClassLoader();
            var resourceUrl = cl.getResource(resourceDir);
            if (resourceUrl == null) return paths;
            var uri = resourceUrl.toURI();
            if ("jar".equals(uri.getScheme())) {
                try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
                    try (Stream<Path> stream = Files.list(fs.getPath(resourceDir))) {
                        stream.filter(p -> p.toString().toLowerCase().endsWith(".sbe")).forEach(paths::add);
                    }
                }
            } else {
                try (Stream<Path> stream = Files.list(Path.of(uri))) {
                    stream.filter(p -> p.toString().toLowerCase().endsWith(".sbe")).forEach(paths::add);
                }
            }
        } catch (IOException | URISyntaxException ignored) {}

        return paths;
    }
}
