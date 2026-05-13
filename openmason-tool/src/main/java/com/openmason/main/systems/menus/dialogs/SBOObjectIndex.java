package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOParser;

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
 * Cached index of every SBO object reachable from the bundled Stonebreak
 * resources. Used by the SBO editor's recipe ingredient picker (and any other
 * "pick an object" UI) to enumerate valid {@code objectId}s.
 *
 * <p>Loaded lazily on first use, refreshable on demand.
 */
public final class SBOObjectIndex {

    private static volatile List<Entry> cached;

    private SBOObjectIndex() {}

    public record Entry(String objectId, String displayName, String objectType) {
        public boolean isBlock() { return "block".equalsIgnoreCase(objectType); }
        public boolean isItem()  { return "item".equalsIgnoreCase(objectType); }
    }

    public static List<Entry> listAll() {
        List<Entry> snapshot = cached;
        if (snapshot == null) {
            synchronized (SBOObjectIndex.class) {
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
        SBOParser parser = new SBOParser();
        for (String resourceDir : new String[]{"sbo/blocks", "sbo/items"}) {
            for (Path p : discover(resourceDir)) {
                try {
                    SBOParser.RawParse raw = parser.parseRaw(p);
                    out.add(new Entry(
                            raw.manifest().objectId(),
                            raw.manifest().objectName(),
                            raw.manifest().objectType()
                    ));
                } catch (IOException ignored) {
                    // best-effort enumeration; skip unreadable files
                }
            }
        }
        out.sort((a, b) -> a.objectId().compareToIgnoreCase(b.objectId()));
        return Collections.unmodifiableList(out);
    }

    /**
     * Locate every {@code .sbo} file reachable under the given resource
     * subdirectory (e.g. {@code "sbo/blocks"}). Prefers the dev filesystem
     * (so freshly-exported files are seen without rebuilding), falls back to
     * the classpath. Package-private so other dialog utilities can reuse the
     * same discovery strategy.
     */
    static List<Path> discover(String resourceDir) {
        List<Path> paths = new ArrayList<>();

        // Dev-launch from project root: scan source tree directly.
        Path[] candidates = {
                Path.of(resourceDir),
                Path.of("stonebreak-game/src/main/resources").resolve(resourceDir),
                Path.of("../stonebreak-game/src/main/resources").resolve(resourceDir)
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                try (Stream<Path> stream = Files.list(candidate)) {
                    stream.filter(p -> p.toString().toLowerCase().endsWith(".sbo")).forEach(paths::add);
                } catch (IOException ignored) {}
                if (!paths.isEmpty()) return paths;
            }
        }

        // Fallback: classpath (openmason-tool depends on stonebreak-game).
        try {
            ClassLoader cl = SBOObjectIndex.class.getClassLoader();
            var resourceUrl = cl.getResource(resourceDir);
            if (resourceUrl == null) return paths;
            var uri = resourceUrl.toURI();
            Path resourcePath;
            if ("jar".equals(uri.getScheme())) {
                FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
                resourcePath = fs.getPath(resourceDir);
            } else {
                resourcePath = Path.of(uri);
            }
            try (Stream<Path> stream = Files.list(resourcePath)) {
                stream.filter(p -> p.toString().toLowerCase().endsWith(".sbo")).forEach(paths::add);
            }
        } catch (IOException | URISyntaxException ignored) {}

        return paths;
    }
}
