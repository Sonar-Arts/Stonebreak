package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.ui.assets.AssetKinds;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.ProjectFolder;
import com.openmason.main.systems.project.ProjectLayout;
import com.stonebreak.ui.runtime.GameUiAssets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The open project as the UI editor sees it: where UI documents and shared UI assets live
 * ({@code <project>/UI/...}, by #285 convention {@code UI/<namespace>/<path><ext>}), the asset
 * sources documents resolve through (project first, then the game's packaged root), and a cached
 * scan of the project's UI files for the palette and the asset panel.
 */
public final class UiProjectContext {

    private static final Logger logger = LoggerFactory.getLogger(UiProjectContext.class);

    /** One UI file in the project. */
    public record Entry(Path path, String relative, Kind kind, String documentId, String displayName,
                        UiManifest.DocumentKind documentKind) {

        public enum Kind { SCREEN, COMPONENT, EXPORT, STYLESHEET, SCRIPT, OTHER }

        public String label() {
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
            return path.getFileName().toString();
        }
    }

    private final Supplier<Path> projectRoot;
    private final Map<Path, CachedManifest> manifests = new HashMap<>();
    private List<Entry> entries = List.of();
    private Path scannedRoot;
    private long scannedAt;

    private record CachedManifest(long modified, OmuiArchive archive) {
    }

    public UiProjectContext(Supplier<Path> projectRoot) {
        this.projectRoot = projectRoot;
    }

    /** The project folder, or null when no project is open. */
    public Path root() {
        Path r = projectRoot == null ? null : projectRoot.get();
        return r == null || !Files.isDirectory(r) ? null : r;
    }

    public ProjectFolder folder() {
        Path r = root();
        return r == null ? null : new ProjectFolder(r);
    }

    public ProjectAssetSource projectSource() {
        return ProjectLayout.uiAssetSource(root());
    }

    /** Sources a document's shared rows resolve through: project first, then the game's packaged root. */
    public List<AssetSource> sources() {
        List<AssetSource> out = new ArrayList<>();
        ProjectAssetSource p = projectSource();
        if (p != null) {
            out.add(p);
        }
        try {
            out.addAll(GameUiAssets.sources(Map.of()));
        } catch (IOException e) {
            logger.warn("Packaged UI assets unavailable: {}", e.getMessage());
        }
        return out;
    }

    /** {@code <project>/UI}, or null. */
    public Path uiDir() {
        Path r = root();
        return r == null ? null : r.resolve(ProjectLayout.UI_DIR);
    }

    /**
     * Where a document with {@code documentId} belongs by convention ({@code UI/<ns>/<path>.omui}),
     * so screens find the components it defines without any configuration. Null without a project.
     */
    public Path conventionPath(String documentId) {
        ProjectAssetSource p = projectSource();
        if (p == null) {
            return null;
        }
        return root().resolve(p.conventionPath(documentId, UiDependency.Kind.COMPONENT, null));
    }

    /** The project-relative form of {@code file} ({@code /} separators), or null outside the project. */
    public String relative(Path file) {
        Path r = root();
        if (r == null || file == null) {
            return null;
        }
        Path abs = file.toAbsolutePath().normalize();
        Path base = r.toAbsolutePath().normalize();
        return abs.startsWith(base) ? base.relativize(abs).toString().replace('\\', '/') : null;
    }

    // ── scanning ────────────────────────────────────────────────────────────

    /** The project's UI files, rescanned when asked or when the cached scan is older than 3 s. */
    public List<Entry> entries(boolean force) {
        Path r = root();
        long now = System.currentTimeMillis();
        if (!force && r != null && r.equals(scannedRoot) && now - scannedAt < 3000) {
            return entries;
        }
        scannedRoot = r;
        scannedAt = now;
        entries = r == null ? List.of() : scan(r);
        return entries;
    }

    /** Reusable components found in the project (palette "Project Components"). */
    public List<Entry> components() {
        return entries(false).stream().filter(e -> e.kind() == Entry.Kind.COMPONENT).toList();
    }

    /** The component archive at {@code entry}, or null when it cannot be read. */
    public OmuiArchive read(Entry entry) {
        return readCached(entry.path());
    }

    /**
     * A shared dependency row for a project file, as #285 records it: id, kind, content hash,
     * size and the project-relative hint.
     */
    public UiDependency sharedRow(String id, UiDependency.Kind kind, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        com.openmason.engine.format.omui.UiBytes b = com.openmason.engine.format.omui.UiBytes.copyOf(bytes);
        return UiDependency.shared(id, kind, b.sha256(), b.size(), relative(file));
    }

    private List<Entry> scan(Path root) {
        List<Entry> out = new ArrayList<>();
        Path ui = root.resolve(ProjectLayout.UI_DIR);
        List<Path> files = new ArrayList<>();
        collect(ui, files, 8);
        collectTopLevel(root, files);
        for (Path f : files) {
            String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
            String rel = relative(f);
            if (name.endsWith(".omui")) {
                OmuiArchive a = readCached(f);
                if (a == null) {
                    out.add(new Entry(f, rel, Entry.Kind.OTHER, null, null, null));
                    continue;
                }
                UiManifest m = a.manifest();
                Entry.Kind k = m.kind() == UiManifest.DocumentKind.COMPONENT ? Entry.Kind.COMPONENT : Entry.Kind.SCREEN;
                out.add(new Entry(f, rel, k, m.documentId(), m.displayName(), m.kind()));
            } else if (name.endsWith(".sbui")) {
                out.add(new Entry(f, rel, Entry.Kind.EXPORT, null, null, null));
            } else if (name.endsWith(".uss.json")) {
                out.add(new Entry(f, rel, Entry.Kind.STYLESHEET, idFor(ui, f), null, null));
            } else if (name.endsWith(".lua")) {
                out.add(new Entry(f, rel, Entry.Kind.SCRIPT, idFor(ui, f), null, null));
            }
        }
        out.sort(Comparator.comparing((Entry e) -> e.kind().ordinal()).thenComparing(e -> e.label().toLowerCase(Locale.ROOT)));
        return List.copyOf(out);
    }

    /** {@code UI/stonebreak/ui/themes/stone.uss.json} → {@code stonebreak:ui/themes/stone}; null off-convention. */
    static String idFor(Path uiDir, Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path base = uiDir.toAbsolutePath().normalize();
        if (!abs.startsWith(base)) {
            return null;
        }
        String rel = base.relativize(abs).toString().replace('\\', '/');
        int slash = rel.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String path = rel.substring(slash + 1);
        for (UiDependency.Kind k : UiDependency.Kind.values()) {
            for (String ext : AssetKinds.extensions(k)) {
                if (path.endsWith(ext)) {
                    path = path.substring(0, path.length() - ext.length());
                    return rel.substring(0, slash) + ":" + path;
                }
            }
        }
        return null;
    }

    private static void collect(Path dir, List<Path> out, int depth) {
        if (depth < 0 || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                if (Files.isDirectory(p)) {
                    if (!p.getFileName().toString().startsWith(".")) {
                        collect(p, out, depth - 1);
                    }
                } else {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            logger.debug("Cannot list {}: {}", dir, e.getMessage());
        }
    }

    private static void collectTopLevel(Path root, List<Path> out) {
        try (Stream<Path> s = Files.list(root)) {
            s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return Files.isRegularFile(p) && (n.endsWith(".omui") || n.endsWith(".sbui"));
            }).forEach(out::add);
        } catch (IOException e) {
            logger.debug("Cannot list {}: {}", root, e.getMessage());
        }
    }

    private OmuiArchive readCached(Path f) {
        try {
            long modified = Files.getLastModifiedTime(f).toMillis();
            CachedManifest c = manifests.get(f);
            if (c != null && c.modified() == modified) {
                return c.archive();
            }
            OmuiArchive a = OmuiReader.read(f).archive();
            manifests.put(f, new CachedManifest(modified, a));
            return a;
        } catch (Exception e) {
            logger.debug("Not a readable UI document {}: {}", f, e.getMessage());
            return null;
        }
    }
}
