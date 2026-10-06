package com.openmason.main.systems.uiEditor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.io.AtomicFiles;
import com.openmason.main.AppPaths;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Crash recovery for UI documents. While a document is dirty its current state is written,
 * atomically, to a recovery slot every {@link #INTERVAL_SECONDS}; a save or a deliberate
 * discard clears the slot. After a crash the slot outlives the session: opening the same file
 * offers the newer recovered state, and untitled documents are listed for restore.
 *
 * <p>The recovery copy is a complete OMUI archive (unknown fields, unreadable components and
 * missing assets included), so restoring never loses nodes the editor could not render.
 */
public final class UiRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(UiRecoveryService.class);
    public static final double INTERVAL_SECONDS = 20;

    /** A recovery slot: the saved state, which file it belongs to (null when untitled), when. */
    public record Slot(Path archive, Path meta, Path file, String title, String documentId, long savedAt) {
    }

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper();
    /** One slot per open document for its whole life, even when an untitled one gets a file. */
    private final java.util.Map<UiEditorDocument, String> keys = new java.util.WeakHashMap<>();
    private double sinceLast;

    public UiRecoveryService() {
        this(AppPaths.dataRoot().resolve("recovery").resolve("ui"));
    }

    public UiRecoveryService(Path dir) {
        this.dir = dir;
    }

    public Path directory() {
        return dir;
    }

    /** Call once per frame; writes every dirty document whose revision changed since its last slot. */
    public void tick(double dt, List<UiEditorDocument> open, java.util.Map<UiEditorDocument, Long> written) {
        sinceLast += dt;
        if (sinceLast < INTERVAL_SECONDS) {
            return;
        }
        sinceLast = 0;
        for (UiEditorDocument d : open) {
            Long last = written.get(d);
            if (d.isDirty() && (last == null || last != d.revision())) {
                if (write(d)) {
                    written.put(d, d.revision());
                }
            }
        }
    }

    /** Writes {@code doc}'s current state to its slot. */
    public boolean write(UiEditorDocument doc) {
        try {
            Files.createDirectories(dir);
            String key = keys.computeIfAbsent(doc, UiRecoveryService::key);
            AtomicFiles.write(dir.resolve(key + ".omui"), OmuiWriter.write(doc.archive()));
            ObjectNode meta = json.createObjectNode();
            if (doc.file() != null) {
                meta.put("file", doc.file().toAbsolutePath().toString());
            }
            meta.put("title", doc.title());
            meta.put("documentId", doc.archive().manifest().documentId());
            meta.put("savedAt", System.currentTimeMillis());
            AtomicFiles.write(dir.resolve(key + ".json"), json.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(meta));
            return true;
        } catch (Exception e) {
            logger.warn("Could not write UI recovery for {}: {}", doc.title(), e.getMessage());
            return false;
        }
    }

    /** Removes {@code doc}'s slot (saved, or discarded on purpose). */
    public void clear(UiEditorDocument doc) {
        String key = keys.remove(doc);
        if (key == null) {
            return; // never written this session: a crashed session's slot for the same file is not ours
        }
        delete(dir.resolve(key + ".omui"));
        delete(dir.resolve(key + ".json"));
    }

    public void clear(Slot slot) {
        delete(slot.archive());
        delete(slot.meta());
    }

    /** Every slot on disk, newest first. */
    public List<Slot> slots() {
        List<Slot> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path meta : s.filter(p -> p.toString().endsWith(".json")).toList()) {
                Path archive = Path.of(meta.toString().replaceFirst("\\.json$", ".omui"));
                if (!Files.isRegularFile(archive)) {
                    continue;
                }
                var node = json.readTree(meta.toFile());
                String file = node.path("file").asText(null);
                out.add(new Slot(archive, meta, file == null ? null : Path.of(file), node.path("title").asText(""),
                    node.path("documentId").asText(""), node.path("savedAt").asLong(0)));
            }
        } catch (IOException e) {
            logger.warn("Cannot list UI recovery slots: {}", e.getMessage());
        }
        out.sort((a, b) -> Long.compare(b.savedAt(), a.savedAt()));
        return out;
    }

    /** The slot for {@code file} when it is newer than the file itself, else null. */
    public Slot newerThan(Path file) {
        Path abs = file.toAbsolutePath();
        for (Slot s : slots()) {
            if (s.file() != null && s.file().equals(abs)) {
                try {
                    long modified = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0;
                    return s.savedAt() > modified ? s : null;
                } catch (IOException e) {
                    return s;
                }
            }
        }
        return null;
    }

    /** Reads a slot's archive. */
    public OmuiArchive read(Slot slot) throws IOException {
        return OmuiReader.read(slot.archive()).archive();
    }

    static String key(UiEditorDocument doc) {
        // Unique per document and session: a crashed session's slot for the same file must never be
        // overwritten or cleared by the reopened document before the author chooses Restore.
        String basis = (doc.file() != null ? doc.file().toAbsolutePath().toString()
            : "untitled:" + doc.archive().manifest().documentId()) + ":" + System.identityHashCode(doc) + ":"
            + System.nanoTime();
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(basis.hashCode());
        }
    }

    private static void delete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            logger.debug("Cannot delete {}: {}", p, e.getMessage());
        }
    }
}
