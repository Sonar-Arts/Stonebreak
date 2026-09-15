package com.openmason.main.systems.menus.dialogs;


import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import com.openmason.main.systems.assets.AssetCatalog;
import com.openmason.main.systems.assets.AssetEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * SBO view over the shared {@link AssetCatalog}. Kept as a thin façade so the
 * recipe ingredient picker (and any other "pick an object" UI) keeps its
 * historical shape while scanning/caching lives in one place.
 */
public final class SBOObjectIndex {

    private SBOObjectIndex() {}

    public record Entry(String objectId, String displayName, String objectType, Path sourcePath) {
        public boolean isBlock() { return "block".equalsIgnoreCase(objectType); }
        public boolean isItem()  { return "item".equalsIgnoreCase(objectType); }
    }

    /** Look up an entry by exact objectId, or null when unknown. */
    public static Entry find(String objectId) {
        if (objectId == null || objectId.isEmpty()) return null;
        for (Entry e : listAll()) {
            if (e.objectId().equals(objectId)) return e;
        }
        return null;
    }

    public static List<Entry> listAll() {
        return AssetCatalog.shared().listAll(false).stream()
                .filter(e -> e.kind() == AssetEntry.Kind.SBO)
                .map(e -> new Entry(e.id(), e.displayName(), e.type(), e.sourcePath()))
                .toList();
    }

    public static void refresh() {
        AssetCatalog.shared().listAll(true);
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
                } catch (java.io.IOException ignored) {}
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
        } catch (java.io.IOException | URISyntaxException ignored) {}

        return paths;
    }
}
