package com.openmason.main.systems.assets;

import com.openmason.api.target.GameTarget;
import com.openmason.engine.format.sbe.SBEParser;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.target.GameTargets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Unified index of every SBO/SBE asset in the active game target's resource tree
 * — the search surface behind the asset-lens ("eyeglass") tools.
 *
 * <p>Scans every folder the target declares for {@code .sbo} and {@code .sbe}
 * assets ({@link com.openmason.api.target.AssetFolders}), recursively. When no
 * dev tree exists (packaged launch), those folders are mirrored from the
 * target's class path into a read-only temp directory so all downstream tooling
 * keeps working on plain {@link Path}s. No target: the catalog is empty.
 *
 * <p>Staleness is detected with a cheap per-root fingerprint (file count +
 * max mtime + total size), probed at most every 2 seconds unless a refresh is
 * forced.
 */
public final class AssetCatalog {

    private static final AssetCatalog SHARED = new AssetCatalog(GameTargets.shared()::active);

    /** Process-wide shared catalog (UI pickers and MCP/harness tools). */
    public static AssetCatalog shared() {
        return SHARED;
    }

    private static final Logger logger = LoggerFactory.getLogger(AssetCatalog.class);
    private static final long PROBE_DEBOUNCE_MS = 2_000;
    private final Supplier<Optional<GameTarget>> target;
    private final Object lock = new Object();
    private List<AssetEntry> entries = List.of();
    private Map<String, Long> fingerprint = Map.of();
    private long lastProbeMillis;
    private boolean scannedOnce;
    private Path classpathMirror;
    /** The target {@link #classpathMirror} was made for (a target switch makes a new one). */
    private String mirrorTarget;

    /** A catalog over whatever target {@code target} names at each scan. */
    public AssetCatalog(Supplier<Optional<GameTarget>> target) {
        this.target = target;
    }

    /** A ranked search outcome. */
    public record SearchResult(List<AssetEntry> entries, int total) {
    }

    /** All entries, rescanning when the tree changed (debounced). */
    public List<AssetEntry> listAll(boolean forceRefresh) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            boolean probe = forceRefresh || !scannedOnce
                    || now - lastProbeMillis >= PROBE_DEBOUNCE_MS;
            if (probe) {
                lastProbeMillis = now;
                Map<String, Long> current = computeFingerprint();
                if (forceRefresh || !scannedOnce || !current.equals(fingerprint)) {
                    entries = scan();
                    fingerprint = current;
                    scannedOnce = true;
                }
            }
            return entries;
        }
    }

    /**
     * Resolve an asset by objectId — exact first, then case-insensitive, then
     * the id without its {@code namespace:} prefix, then the file name (with
     * or without extension). Games ship ids like {@code <ns>:oak_door} in files
     * named {@code <prefix>Oak_Door.sbo}, so all four spellings must work.
     */
    public AssetEntry find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        List<AssetEntry> all = listAll(false);
        for (AssetEntry e : all) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        for (AssetEntry e : all) {
            if (e.id().equalsIgnoreCase(id)) {
                return e;
            }
        }
        for (AssetEntry e : all) {
            if (localId(e.id()).equalsIgnoreCase(id)) {
                return e;
            }
        }
        for (AssetEntry e : all) {
            String file = e.sourcePath().getFileName().toString();
            String bare = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
            if (file.equalsIgnoreCase(id) || bare.equalsIgnoreCase(id)) {
                return e;
            }
        }
        return null;
    }

    private static String localId(String id) {
        int idx = id.indexOf(':');
        return idx >= 0 ? id.substring(idx + 1) : id;
    }

    /**
     * Ranked substring search.
     *
     * @param query case-insensitive substring of objectId or display name; null/blank = all
     * @param kind  filter, or null
     * @param type  exact type filter (block/item/mob/…), or null
     */
    public SearchResult search(String query, AssetEntry.Kind kind, String type,
                               int limit, int offset, boolean forceRefresh) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String t = type == null ? null : type.trim().toLowerCase(Locale.ROOT);
        record Ranked(int rank, AssetEntry entry) {
        }
        List<Ranked> ranked = new ArrayList<>();
        for (AssetEntry e : listAll(forceRefresh)) {
            if (kind != null && e.kind() != kind) {
                continue;
            }
            if (t != null && !t.isEmpty() && !t.equals(e.type())) {
                continue;
            }
            int rank;
            if (q.isEmpty()) {
                rank = 0;
            } else {
                String id = e.id().toLowerCase(Locale.ROOT);
                String local = localId(id);
                String name = e.displayName() == null ? "" : e.displayName().toLowerCase(Locale.ROOT);
                String file = e.sourcePath().getFileName().toString().toLowerCase(Locale.ROOT);
                if (id.equals(q) || local.equals(q)) {
                    rank = 0;
                } else if (id.startsWith(q) || local.startsWith(q) || file.startsWith(q)) {
                    rank = 1;
                } else if (name.startsWith(q)) {
                    rank = 2;
                } else if (id.contains(q) || file.contains(q)) {
                    rank = 3;
                } else if (name.contains(q)) {
                    rank = 4;
                } else {
                    continue;
                }
            }
            ranked.add(new Ranked(rank, e));
        }
        ranked.sort((a, b) -> a.rank != b.rank ? Integer.compare(a.rank, b.rank)
                : a.entry.id().compareToIgnoreCase(b.entry.id()));
        int total = ranked.size();
        List<AssetEntry> page = ranked.stream()
                .skip(Math.max(0, offset))
                .limit(Math.max(0, limit))
                .map(Ranked::entry)
                .toList();
        return new SearchResult(page, total);
    }

    /** Closest known ids for did-you-mean error messages. */
    public List<String> candidatesFor(String id, int max) {
        String q = id == null ? "" : id.toLowerCase(Locale.ROOT);
        return listAll(false).stream()
                .map(AssetEntry::id)
                .sorted((a, b) -> Integer.compare(distanceScore(a, q), distanceScore(b, q)))
                .limit(max)
                .toList();
    }

    private static int distanceScore(String candidate, String query) {
        String c = candidate.toLowerCase(Locale.ROOT);
        if (c.contains(query) || query.contains(c)) {
            return Math.abs(c.length() - query.length());
        }
        // Cheap common-prefix heuristic: longer shared prefix = closer.
        int shared = 0;
        int n = Math.min(c.length(), query.length());
        while (shared < n && c.charAt(shared) == query.charAt(shared)) {
            shared++;
        }
        return 100 - shared;
    }

    /** Absolute scan roots that currently exist (for path-guard whitelisting). */
    public List<Path> scanRoots() {
        List<Path> roots = new ArrayList<>();
        Path resources = resourcesRoot();
        if (resources != null) {
            roots.add(resources);
        }
        synchronized (lock) {
            if (classpathMirror != null) {
                roots.add(classpathMirror);
            }
        }
        return roots;
    }

    // ------------------------------------------------------------------ scan

    private Map<String, Long> computeFingerprint() {
        Map<String, Long> fp = new HashMap<>();
        fp.put("@target", (long) target.get().map(GameTarget::id).orElse("").hashCode());
        for (Path dir : scanDirs()) {
            long acc = 0;
            long count = 0;
            try (Stream<Path> stream = Files.list(dir)) {
                for (Path p : stream.filter(AssetCatalog::isAsset).toList()) {
                    count++;
                    try {
                        FileTime mtime = Files.getLastModifiedTime(p);
                        acc = Math.max(acc, mtime.toMillis());
                        acc += Files.size(p) % 8191;
                    } catch (IOException ignored) {
                        // unreadable entry — count alone still shifts the fingerprint
                    }
                }
            } catch (IOException ignored) {
                continue;
            }
            fp.put(dir.toString(), acc * 31 + count);
        }
        return fp;
    }

    private Path resourcesRoot() {
        return target.get().map(t -> t.resources().locate()).orElse(null);
    }

    /** The target's {@code .sbo} then {@code .sbe} folders (resource-relative), no repeats. */
    private static List<String> assetFolders(GameTarget t) {
        List<String> out = new ArrayList<>(t.folders().foldersOf(BuiltinFileTypes.SBO.id()));
        for (String f : t.folders().foldersOf(BuiltinFileTypes.SBE.id())) {
            if (!out.contains(f)) {
                out.add(f);
            }
        }
        return out;
    }

    private List<Path> scanDirs() {
        List<Path> dirs = new ArrayList<>();
        Optional<GameTarget> t = target.get();
        if (t.isEmpty()) {
            return dirs;
        }
        Path resources = t.get().resources().locate();
        if (resources != null) {
            // Every declared folder and its sub-folders; a base folder covers its subtypes'.
            java.util.Set<Path> seen = new java.util.LinkedHashSet<>();
            for (String rel : assetFolders(t.get())) {
                Path dir = resources.resolve(rel);
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> stream = Files.walk(dir)) {
                    stream.filter(Files::isDirectory).forEach(seen::add);
                } catch (IOException ignored) {
                    seen.add(dir);
                }
            }
            dirs.addAll(seen);
        } else {
            Path mirror = ensureClasspathMirror(t.get());
            if (mirror != null) {
                try (Stream<Path> stream = Files.walk(mirror)) {
                    stream.filter(Files::isDirectory).forEach(dirs::add);
                } catch (IOException ignored) {
                }
            }
        }
        return dirs;
    }

    private List<AssetEntry> scan() {
        List<AssetEntry> out = new ArrayList<>();
        SBOParser sboParser = new SBOParser();
        SBEParser sbeParser = new SBEParser();
        Optional<GameTarget> t = target.get();
        Path resources = resourcesRoot();
        Path base = resources != null ? resources : t.map(this::ensureClasspathMirror).orElse(null);
        for (Path dir : scanDirs()) {
            List<Path> files;
            try (Stream<Path> stream = Files.list(dir)) {
                files = stream.filter(AssetCatalog::isAsset).sorted().toList();
            } catch (IOException e) {
                continue;
            }
            for (Path p : files) {
                String folder = base != null && p.getParent().startsWith(base)
                        ? base.relativize(p.getParent()).toString().replace('\\', '/')
                        : p.getParent().toString();
                try {
                    long size = Files.size(p);
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    if (BuiltinFileTypes.SBO.matches(p)) {
                        SBOParser.RawParse raw = sboParser.parseRaw(p);
                        out.add(new AssetEntry(raw.manifest().objectId(), raw.manifest().objectName(),
                                AssetEntry.Kind.SBO, lower(raw.manifest().objectType(), "model"),
                                p.toAbsolutePath(), folder, size, mtime));
                    } else {
                        SBEParser.RawParse raw = sbeParser.parseRaw(p);
                        String type = lower(raw.manifest().entityType(), folderType(t.orElse(null), folder));
                        out.add(new AssetEntry(raw.manifest().objectId(), raw.manifest().objectName(),
                                AssetEntry.Kind.SBE, type, p.toAbsolutePath(), folder, size, mtime));
                    }
                } catch (IOException | RuntimeException e) {
                    logger.debug("Skipping unreadable asset {}: {}", p, e.toString());
                }
            }
        }
        out.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return Collections.unmodifiableList(out);
    }

    private static boolean isAsset(Path p) {
        return Files.isRegularFile(p) && (BuiltinFileTypes.SBO.matches(p) || BuiltinFileTypes.SBE.matches(p));
    }

    private static String lower(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim().toLowerCase(Locale.ROOT);
    }

    /** The entity type the target files {@code folder} under ({@code sbe/Mobs} → {@code mob}), else {@code other}. */
    private static String folderType(GameTarget t, String folder) {
        String type = t == null || folder == null ? null : t.folders().subtypeOf(BuiltinFileTypes.SBE.id(), folder);
        return type == null ? "other" : type;
    }

    /**
     * Packaged-launch fallback: copy known classpath asset folders into a temp
     * mirror once per catalog lifetime so every downstream consumer works on
     * real read-only files.
     */
    private Path ensureClasspathMirror(GameTarget t) {
        synchronized (lock) {
            if (classpathMirror != null && t.id().equals(mirrorTarget)) {
                return classpathMirror;
            }
            try {
                Path mirror = Files.createTempDirectory("openmason-asset-mirror");
                mirror.toFile().deleteOnExit();
                ClassLoader cl = t.resources().classpath();
                if (cl == null) {
                    return null;
                }
                int copied = 0;
                for (String folder : assetFolders(t)) {
                    copied += mirrorFolder(cl, folder, mirror);
                }
                if (copied == 0) {
                    return null; // nothing on classpath either
                }
                classpathMirror = mirror;
                mirrorTarget = t.id();
                logger.info("Asset catalog: mirrored {} classpath assets into {}", copied, mirror);
                return mirror;
            } catch (IOException e) {
                logger.warn("Asset catalog: classpath mirror failed: {}", e.toString());
                return null;
            }
        }
    }

    private static int mirrorFolder(ClassLoader cl, String folder, Path mirror) {
        // Enumerate via the SBOObjectIndex-style jar filesystem walk.
        int copied = 0;
        try {
            var url = cl.getResource(folder);
            if (url == null) {
                return 0;
            }
            var uri = url.toURI();
            Path source;
            java.nio.file.FileSystem fs = null;
            if ("jar".equals(uri.getScheme())) {
                try {
                    fs = java.nio.file.FileSystems.getFileSystem(uri);
                } catch (RuntimeException e) {
                    fs = java.nio.file.FileSystems.newFileSystem(uri, Map.of());
                }
                source = fs.getPath(folder);
            } else {
                source = Path.of(uri);
            }
            Path target = mirror.resolve(folder);
            Files.createDirectories(target);
            try (Stream<Path> stream = Files.list(source)) {
                for (Path p : stream.filter(AssetCatalog::isAsset).toList()) {
                    Path dest = target.resolve(p.getFileName().toString());
                    try (InputStream in = Files.newInputStream(p)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                        copied++;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("classpath mirror of {} failed: {}", folder, e.toString());
        }
        return copied;
    }

    @Override
    public String toString() {
        return "AssetCatalog{" + listAll(false).size() + " assets}";
    }

    // test seam
    void resetForTest() {
        synchronized (lock) {
            entries = List.of();
            fingerprint = Map.of();
            scannedOnce = false;
            lastProbeMillis = 0;
        }
    }
}
