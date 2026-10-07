package com.openmason.engine.format.uiarchive;

import com.openmason.engine.format.omui.ArchiveLimits;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.ArchiveIO;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.omui.io.EntryPaths;
import com.openmason.engine.format.sbui.SbuiFormat;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.format.sbui.SbuiWriter;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Source-directory form of OMUI/SBUI archives for Git review. {@link #unpack} writes the
 * canonical entries as plain files; {@link #pack} reads a directory back, validates it like
 * any archive and emits canonical, deterministic archive bytes (so hand edits are normalized,
 * never trusted). Files and directories whose name starts with {@code .} are ignored by
 * {@code pack}; symbolic links are refused.
 */
public final class UiPacker {

    private UiPacker() {
    }

    /** Which container a set of entries is, from its manifest's {@code format}. */
    public enum Kind { OMUI, SBUI }

    public static byte[] pack(Path dir) throws IOException {
        Map<String, byte[]> entries = readDirectory(dir);
        return switch (detect(entries)) {
            case OMUI -> OmuiWriter.write(OmuiReader.fromEntries(entries, ArchiveLimits.DEFAULT).archive());
            case SBUI -> SbuiWriter.write(SbuiReader.fromEntries(entries, SbuiReader.Options.RUNTIME).archive());
        };
    }

    /**
     * Validate {@code archive} and write its canonical entries under {@code dir}. The directory
     * must be absent or empty unless {@code replace}; the files appear all at once (written to a
     * sibling and renamed), never half-written.
     */
    public static void unpack(byte[] archive, Path dir, boolean replace) throws IOException {
        UiDiagnostics d = new UiDiagnostics();
        Map<String, byte[]> raw = ArchiveIO.read(archive, ArchiveLimits.DEFAULT, d);
        d.throwIfErrors("Cannot open archive");
        Map<String, UiBytes> canonical = switch (detect(raw)) {
            case OMUI -> OmuiWriter.entries(OmuiReader.fromEntries(raw, ArchiveLimits.DEFAULT).archive());
            case SBUI -> {
                // Validated, then written as stored: the embedded OMUI and caches are opaque blobs.
                // Same policy as pack (and SbuiWriter): an export carrying a stale derived cache is
                // refused both ways, so a tree that unpacks always packs again. Re-export it.
                SbuiReader.fromEntries(raw, SbuiReader.Options.RUNTIME);
                Map<String, UiBytes> verbatim = new LinkedHashMap<>();
                raw.forEach((k, v) -> verbatim.put(k, UiBytes.copyOf(v)));
                yield verbatim;
            }
        };
        Path target = dir.toAbsolutePath().normalize();
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists && !replace && !isEmptyDirectory(target)) {
            throw new IOException("Refusing to unpack into non-empty " + target + " (use replace)");
        }
        Path staging = Files.createTempDirectory(target.getParent(), "." + target.getFileName() + ".");
        try {
            for (Map.Entry<String, UiBytes> e : canonical.entrySet()) {
                Path file = staging.resolve(e.getKey()).normalize();
                if (!file.startsWith(staging)) {
                    throw new IOException("Entry escapes the directory: " + e.getKey());
                }
                Files.createDirectories(file.getParent());
                Files.write(file, e.getValue().toArray());
            }
            if (exists) {
                Path old = Files.createTempDirectory(target.getParent(), "." + target.getFileName() + ".old.");
                Files.delete(old);
                Files.move(target, old);
                try {
                    Files.move(staging, target);
                } catch (IOException e) {
                    Files.move(old, target); // roll back: the original returns to its place
                    throw e;
                }
                deleteTree(old);
            } else {
                Files.move(staging, target);
            }
        } finally {
            if (Files.exists(staging)) {
                deleteTree(staging);
            }
        }
    }

    /** Which container {@code archive} is; fails on anything that is not a readable UI archive. */
    public static Kind detect(byte[] archive) throws UiFormatException {
        UiDiagnostics d = new UiDiagnostics();
        Map<String, byte[]> raw = ArchiveIO.read(archive, ArchiveLimits.DEFAULT, d);
        d.throwIfErrors("Cannot open archive");
        return detect(raw);
    }

    static Kind detect(Map<String, byte[]> entries) throws UiFormatException {
        UiDiagnostics d = new UiDiagnostics();
        byte[] manifest = entries.get(OmuiFormat.MANIFEST);
        if (manifest == null) {
            d.error(Code.MISSING_ENTRY, OmuiFormat.MANIFEST, "", "No manifest.json");
            d.throwIfErrors("Not a UI archive");
        }
        UiValue json = CanonicalJson.parse(manifest, OmuiFormat.MANIFEST, d);
        d.throwIfErrors("Not a UI archive");
        if (json instanceof UiValue.Obj o && o.get("format") instanceof UiValue.Str s) {
            if (s.value().equals(OmuiFormat.FORMAT_ID)) {
                return Kind.OMUI;
            }
            if (s.value().equals(SbuiFormat.FORMAT_ID)) {
                return Kind.SBUI;
            }
        }
        d.error(Code.NOT_AN_ARCHIVE, OmuiFormat.MANIFEST, "/format", "Expected format \"omui\" or \"sbui\"");
        throw new UiFormatException("Not a UI archive", d.list());
    }

    private static Map<String, byte[]> readDirectory(Path dir) throws IOException {
        ArchiveLimits limits = ArchiveLimits.DEFAULT;
        UiDiagnostics d = new UiDiagnostics();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long[] total = {0};
        Path root = dir.toAbsolutePath().normalize();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes attrs) {
                return !p.equals(root) && p.getFileName().toString().startsWith(".")
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) throws IOException {
                if (p.getFileName().toString().startsWith(".")) {
                    return FileVisitResult.CONTINUE;
                }
                String name = root.relativize(p).toString().replace(p.getFileSystem().getSeparator(), "/");
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                    d.error(Code.UNSAFE_ENTRY_PATH, name, "", "Not a regular file");
                    return FileVisitResult.TERMINATE;
                }
                String problem = EntryPaths.problem(name);
                total[0] += attrs.size();
                if (problem != null || entries.size() >= limits.maxEntries() || attrs.size() > limits.maxEntryBytes()
                        || total[0] > limits.maxTotalBytes()) {
                    d.error(problem != null ? Code.UNSAFE_ENTRY_PATH : Code.LIMIT_EXCEEDED, name, "",
                            problem != null ? problem : "Directory exceeds archive limits");
                    return FileVisitResult.TERMINATE;
                }
                entries.put(name, Files.readAllBytes(p));
                return FileVisitResult.CONTINUE;
            }
        });
        d.throwIfErrors("Cannot pack " + dir);
        Map<String, String> folded = new LinkedHashMap<>();
        for (String name : entries.keySet()) {
            String prev = folded.putIfAbsent(EntryPaths.collisionKey(name), name);
            if (prev != null) {
                d.error(Code.DUPLICATE_ENTRY, name, "", "Collides with '" + prev + "' on a case-insensitive filesystem");
            }
        }
        d.throwIfErrors("Cannot pack " + dir);
        return entries;
    }

    private static boolean isEmptyDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** {@link #unpack(byte[], Path, boolean)} from a file. */
    public static void unpack(Path archive, Path dir, boolean replace) throws IOException {
        unpack(Files.readAllBytes(archive), dir, replace);
    }
}
