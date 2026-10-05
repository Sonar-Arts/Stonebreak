package com.openmason.engine.ui.assets.edit;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostic.Severity;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.io.EntryPaths;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.ResolvedAsset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small immutable-document helpers shared by the asset commands. */
final class DocumentEdits {

    private DocumentEdits() {
    }

    static UiDependency row(OmuiArchive doc, String id) throws UiFormatException {
        UiDependency row = doc.dependencies().find(id);
        if (row == null) {
            throw fail(Code.UNRESOLVED_REFERENCE, "'" + id + "' is not in the document's dependency table");
        }
        return row;
    }

    static UiFormatException fail(Code code, String message) {
        return new UiFormatException(message, List.of(
                new UiDiagnostic(Severity.ERROR, code, OmuiFormat.DEPENDENCIES, "", message)));
    }

    /** {@code row} rewritten with a new placement; every other field (and unknown data) kept. */
    static UiDependency place(UiDependency row, UiDependency.Mode mode, String entry, UiBytes bytes, String hint) {
        return new UiDependency(row.id(), row.kind(), row.version(), bytes.sha256(), bytes.size(), mode, entry, hint,
                row.requires(), row.optional(), row.fallback(), row.license(), row.unknown());
    }

    static OmuiArchive withRows(OmuiArchive doc, Map<String, UiDependency> replaced) {
        List<UiDependency> rows = new ArrayList<>();
        for (UiDependency d : doc.dependencies().entries()) {
            rows.add(replaced.getOrDefault(d.id(), d));
        }
        return doc.withDependencies(new UiDependencies(rows, doc.dependencies().unknown()));
    }

    /** Drops {@code entry} unless another row still references it. */
    static OmuiArchive withoutAssetIfUnused(OmuiArchive doc, String entry) {
        for (UiDependency d : doc.dependencies().entries()) {
            if (entry.equals(d.entry())) {
                return doc;
            }
        }
        Map<String, UiBytes> assets = new LinkedHashMap<>(doc.assets());
        assets.remove(entry);
        return new OmuiArchive(doc.manifest(), doc.document(), doc.styles(), doc.graphs(), doc.animations(),
                doc.scripts(), doc.dependencies(), assets, doc.editor(), doc.extraEntries());
    }

    /**
     * {@code wanted} when it is free or already holds {@code bytes}; otherwise the first
     * {@code name-2.ext}, {@code name-3.ext}, ... that is. Existing entries (orphans included)
     * are never overwritten, and names are compared the way archives collide (case-insensitive,
     * file vs directory).
     */
    static String uniqueEntry(Map<String, UiBytes> assets, String wanted, UiBytes bytes) throws UiFormatException {
        String problem = EntryPaths.problem(wanted);
        if (problem != null) {
            throw fail(Code.UNSAFE_ENTRY_PATH, "Cannot embed as '" + wanted + "': " + problem);
        }
        int slash = wanted.lastIndexOf('/');
        String dir = wanted.substring(0, slash + 1);
        String file = wanted.substring(slash + 1);
        int dot = file.indexOf('.', 1);
        String stem = dot < 0 ? file : file.substring(0, dot);
        String ext = dot < 0 ? "" : file.substring(dot);
        for (int n = 1; n <= OmuiFormat.MAX_DEPENDENCIES + 1; n++) {
            String candidate = n == 1 ? wanted : dir + stem + "-" + n + ext;
            UiBytes there = assets.get(candidate);
            if (there != null) {
                if (there.equals(bytes)) {
                    return candidate;
                }
                continue;
            }
            if (!collides(assets.keySet(), candidate)) {
                return candidate;
            }
        }
        throw fail(Code.LIMIT_EXCEEDED, "No free entry name for '" + wanted + "'");
    }

    private static boolean collides(Set<String> names, String candidate) {
        String key = EntryPaths.collisionKey(candidate);
        for (String n : names) {
            String k = EntryPaths.collisionKey(n);
            if (k.equals(key) || k.startsWith(key + "/") || key.startsWith(k + "/")) {
                return true;
            }
        }
        return false;
    }

    /** The first default-root source holding {@code row}'s id: the shared original. */
    static ResolvedAsset findShared(List<? extends AssetSource> sources, UiDependency row) throws UiFormatException {
        for (AssetSource s : sources) {
            if (s.packId() != null) {
                continue;
            }
            try {
                ResolvedAsset a = s.find(row.id(), row.kind(), row.sourceHint());
                if (a != null) {
                    return a;
                }
            } catch (IOException e) {
                throw fail(Code.MISSING_ENTRY, "Cannot read '" + row.id() + "' from " + s.name() + ": " + e.getMessage());
            }
        }
        return null;
    }

    /** The edited document must still be writable, so a command can never produce an unsavable file. */
    static OmuiArchive validated(OmuiArchive doc) throws UiFormatException {
        OmuiWriter.entries(doc);
        return doc;
    }
}
