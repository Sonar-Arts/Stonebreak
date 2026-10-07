package com.openmason.main.systems.uiEditor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiFormatException;
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

    /**
     * Writes {@code doc}'s current state to its slot. A document the writer refuses (an edit
     * left a format error) still gets a slot: the newest state in its history that does write,
     * so a crash loses only the edits since that state, never everything since the last save.
     */
    public boolean write(UiEditorDocument doc) {
        try {
            Files.createDirectories(dir);
            byte[] bytes = null;
            int skipped = 0;
            String refused = null;
            for (OmuiArchive state : candidates(doc)) {
                try {
                    bytes = OmuiWriter.write(state);
                    break;
                } catch (UiFormatException e) {
                    if (refused == null) {
                        refused = e.diagnostics().isEmpty() ? e.getMessage() : e.diagnostics().getFirst().message();
                    }
                    skipped++;
                }
            }
            if (bytes == null) {
                logger.warn("Could not write UI recovery for {}: no state in its history is saveable ({})",
                    doc.title(), refused);
                return false;
            }
            String key = keys.computeIfAbsent(doc, UiRecoveryService::key);
            AtomicFiles.write(dir.resolve(key + ".omui"), bytes);
            ObjectNode meta = json.createObjectNode();
            if (doc.file() != null) {
                meta.put("file", normalize(doc.file()).toString());
            }
            meta.put("title", doc.title());
            meta.put("documentId", doc.archive().manifest().documentId());
            meta.put("savedAt", System.currentTimeMillis());
            if (skipped > 0) {
                // the newest edits make the document unsaveable: recovery holds the last good state
                meta.put("skippedEdits", skipped);
                meta.put("reason", refused);
                doc.setLastMessage("The document cannot be saved (" + refused + "); crash recovery keeps the"
                    + " state before the last " + skipped + " edit(s)");
            }
            AtomicFiles.write(dir.resolve(key + ".json"), json.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(meta));
            return true;
        } catch (Exception e) {
            logger.warn("Could not write UI recovery for {}: {}", doc.title(), e.getMessage());
            return false;
        }
    }

    /** The document's current state, then older states from its history, newest first. */
    private static List<OmuiArchive> candidates(UiEditorDocument doc) {
        List<OmuiArchive> out = new ArrayList<>();
        out.add(doc.archive());
        for (OmuiArchive a : doc.history().recentStates()) {
            if (out.getLast() != a) {
                out.add(a);
            }
        }
        return out;
    }

    /** True when {@code slot} was written by this session (an open document's own autosave). */
    public boolean isOwn(Slot slot) {
        String name = slot.meta().getFileName().toString();
        String key = name.substring(0, name.length() - ".json".length());
        return keys.containsValue(key);
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

    /**
     * Writes {@code doc}'s slot one last time and lets go of it: the document is closing with
     * unsaved changes it was told to drop, but the slot outlives it so the author can still
     * restore them (offered like a crashed session's slot). False when nothing could be kept.
     */
    public boolean release(UiEditorDocument doc) {
        boolean kept = write(doc);
        keys.remove(doc);
        return kept;
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

    /**
     * A slot for {@code file} that is newer than the file itself and was left by another
     * session (a crash), else null. This session's own autosaves never count: they are the
     * open document's state, which saving writes anyway.
     */
    public Slot newerThan(Path file) {
        Path abs = normalize(file);
        for (Slot s : slots()) {
            if (s.file() != null && normalize(s.file()).equals(abs) && !isOwn(s)) {
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

    /** Absolute, normalized and, when the file exists, with symbolic links resolved. */
    static Path normalize(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            return Files.exists(abs) ? abs.toRealPath() : abs;
        } catch (IOException e) {
            return abs;
        }
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
