package com.openmason.main.systems.assets;

import com.openmason.engine.format.sbe.SBEParser;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.main.systems.menus.dialogs.GameResourceDirs;
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
import java.util.stream.Stream;

/**
 * Unified index of every SBO/SBE asset in the game resource tree — the search
 * surface behind the asset-lens ("eyeglass") tools.
 *
 * <p>Scans {@code sbo/blocks}, {@code sbo/items}, {@code sbo/models} and every
 * sub-folder of {@code sbe/} (recursively) under
 * {@link GameResourceDirs#resourcesRoot()}. When no dev tree exists (packaged
 * launch), known resource folders are mirrored from the classpath into a
 * read-only temp directory so all downstream tooling keeps working on plain
 * {@link Path}s.
 *
 * <p>Staleness is detected with a cheap per-root fingerprint (file count +
 * max mtime + total size), probed at most every 2 seconds unless a refresh is
 * forced.
 */
public final class AssetCatalog {

    private static final AssetCatalog SHARED = new AssetCatalog();

    /** Process-wide shared catalog (UI pickers and MCP/harness tools). */
    public static AssetCatalog shared() {
        return SHARED;
    }

    private static final Logger logger = LoggerFactory.getLogger(AssetCatalog.class);
    private static final long PROBE_DEBOUNCE_MS = 2_000;
    private static final String[] SBO_FOLDERS = {"sbo/blocks", "sbo/items", "sbo/models"};
    private static final String[] CLASSPATH_SBE_FOLDERS =
            {"sbe/Mobs", "sbe/Clothing", "sbe/PlayerCustomize", "sbe/Particles"};

    private final Object lock = new Object();
    private List<AssetEntry> entries = List.of();
    private Map<String, Long> fingerprint = Map.of();
    private long lastProbeMillis;
    private boolean scannedOnce;
    private Path classpathMirror;

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
     * or without extension). Ships ids like {@code stonebreak:oak_door} in
     * files named {@code SB_Oak_Door.sbo}, so all four spellings must work.
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
        Path resources = GameResourceDirs.resourcesRoot();
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

    private List<Path> scanDirs() {
        List<Path> dirs = new ArrayList<>();
        Path resources = GameResourceDirs.resourcesRoot();
        if (resources != null) {
            for (String rel : SBO_FOLDERS) {
                Path dir = resources.resolve(rel);
                if (Files.isDirectory(dir)) {
                    dirs.add(dir);
                }
            }
            Path sbe = resources.resolve("sbe");
            if (Files.isDirectory(sbe)) {
                dirs.add(sbe);
                try (Stream<Path> stream = Files.walk(sbe)) {
                    stream.filter(Files::isDirectory)
                            .filter(d -> !d.equals(sbe))
                            .forEach(dirs::add);
                } catch (IOException ignored) {
                    // top-level sbe scan still applies
                }
            }
        } else {
            Path mirror = ensureClasspathMirror();
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
        Path resources = GameResourceDirs.resourcesRoot();
        Path base = resources != null ? resources : ensureClasspathMirror();
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
                    String lower = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".sbo")) {
                        SBOParser.RawParse raw = sboParser.parseRaw(p);
                        out.add(new AssetEntry(raw.manifest().objectId(), raw.manifest().objectName(),
                                AssetEntry.Kind.SBO, lower(raw.manifest().objectType(), "model"),
                                p.toAbsolutePath(), folder, size, mtime));
                    } else {
                        SBEParser.RawParse raw = sbeParser.parseRaw(p);
                        String type = lower(raw.manifest().entityType(), folderType(folder));
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
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(p) && (n.endsWith(".sbo") || n.endsWith(".sbe"));
    }

    private static String lower(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String folderType(String folder) {
        String name = folder == null ? "" : folder.substring(folder.lastIndexOf('/') + 1);
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "mobs" -> "mob";
            case "clothing" -> "clothing";
            case "playercustomize" -> "playercustomize";
            case "particles" -> "particle";
            default -> "other";
        };
    }

    /**
     * Packaged-launch fallback: copy known classpath asset folders into a temp
     * mirror once per catalog lifetime so every downstream consumer works on
     * real read-only files.
     */
    private Path ensureClasspathMirror() {
        synchronized (lock) {
            if (classpathMirror != null) {
                return classpathMirror;
            }
            try {
                Path mirror = Files.createTempDirectory("openmason-asset-mirror");
                mirror.toFile().deleteOnExit();
                ClassLoader cl = AssetCatalog.class.getClassLoader();
                List<String> folders = new ArrayList<>(List.of(SBO_FOLDERS));
                folders.addAll(List.of(CLASSPATH_SBE_FOLDERS));
                int copied = 0;
                for (String folder : folders) {
                    copied += mirrorFolder(cl, folder, mirror);
                }
                if (copied == 0) {
                    return null; // nothing on classpath either
                }
                classpathMirror = mirror;
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
