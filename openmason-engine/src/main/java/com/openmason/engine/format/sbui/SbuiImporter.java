package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.OmuiValidator;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.omui.io.EntryPaths;
import com.openmason.engine.format.omui.io.ObjWriter;
import com.openmason.engine.format.sbui.SbuiManifest.Location;
import com.openmason.engine.format.sbui.SbuiManifest.SbuiDependency;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an SBUI back into an editable OMUI. The embedded canonical OMUI <em>is</em> the
 * editable copy — nothing is reconstructed from runtime data — plus a provenance record in
 * {@code editor/provenance.json} (editor-only, ignored by runtimes). Scripts stay source and
 * are never run. Derived caches are dropped: the editor regenerates them from their sources.
 */
public final class SbuiImporter {

    public static final String PROVENANCE_ENTRY = OmuiFormat.EDITOR_DIR + "provenance.json";

    /**
     * @param document  editable OMUI with provenance
     * @param collected dependencies the export carried in its own {@code assets/} (id → bytes),
     *                  for #285 to import into the project
     */
    public record Imported(OmuiArchive document, Map<String, UiBytes> collected) {
    }

    private SbuiImporter() {
    }

    /** Editable copy that keeps shared dependencies shared. */
    public static Imported importEditable(SbuiArchive sbui) {
        Map<String, UiBytes> collected = new LinkedHashMap<>();
        for (SbuiDependency row : sbui.manifest().dependencies()) {
            if (row.location() == Location.SBUI) {
                collected.put(row.id(), sbui.assets().get(row.entry()));
            }
        }
        OmuiArchive doc = sbui.source().withEditorEntry(PROVENANCE_ENTRY, provenance(sbui));
        return new Imported(doc, collected);
    }

    /**
     * Editable copy that turns every collected dependency into an embedded snapshot inside the
     * OMUI, so the document opens in a clean project with all of its assets.
     *
     * <p>A collected entry name can coincide with one of the source's own embedded assets
     * (ids may contain {@code .}: {@code x:ui/a.png} without a hint and {@code x:ui/a} with a
     * {@code .png} hint both collect to {@code assets/x/ui/a.png}); the collected copy then
     * moves to a free sibling name instead of overwriting the embedded bytes.
     *
     * @throws UiFormatException when the result would not be a valid OMUI document
     */
    public static Imported importPortable(SbuiArchive sbui) throws UiFormatException {
        Imported plain = importEditable(sbui);
        OmuiArchive doc = plain.document();
        Set<String> taken = new HashSet<>();
        for (String entry : doc.assets().keySet()) {
            taken.add(EntryPaths.collisionKey(entry));
        }
        List<UiDependency> rows = new ArrayList<>();
        for (UiDependency dep : doc.dependencies().entries()) {
            UiBytes bytes = plain.collected().get(dep.id());
            if (bytes == null) {
                rows.add(dep);
                continue;
            }
            String entry = freeEntry(sbui.manifest().dependency(dep.id()).entry(), taken); // already assets/...
            taken.add(EntryPaths.collisionKey(entry));
            doc = doc.withAsset(entry, bytes);
            rows.add(new UiDependency(dep.id(), dep.kind(), dep.version(), bytes.sha256(), bytes.size(),
                    UiDependency.Mode.EMBEDDED, entry, dep.sourceHint(), dep.requires(), dep.optional(), dep.fallback(),
                    dep.license(), dep.unknown()));
        }
        doc = doc.withDependencies(new OmuiArchive.UiDependencies(rows, doc.dependencies().unknown()));
        UiDiagnostics d = new UiDiagnostics();
        OmuiValidator.validate(doc, d);
        d.throwIfErrors("Portable import of '" + sbui.manifest().assetId() + "' is not a valid document");
        return new Imported(doc, plain.collected());
    }

    /**
     * {@code entry}, or the first free alternative that neither equals a {@code taken} name
     * (case-folded) nor overlaps one as a file/directory pair: {@code <stem>~N<ext>} in the same
     * directory, or — when a taken file occupies one of the entry's directories, which no sibling
     * name can fix — the same path under {@code <first dir>/collected~N/}.
     */
    static String freeEntry(String entry, Set<String> taken) {
        if (!clashes(entry, taken)) {
            return entry;
        }
        int slash = entry.lastIndexOf('/');
        int dot = entry.lastIndexOf('.');
        String stem = dot > slash + 1 ? entry.substring(0, dot) : entry;
        String ext = dot > slash + 1 ? entry.substring(dot) : "";
        boolean dirBlocked = fileOnPath(EntryPaths.collisionKey(entry), taken);
        int first = entry.indexOf('/');
        // Each candidate is distinct, so one of the first taken.size() + 1 is free unless the
        // top-level directory itself is a taken file, which no rename under it can fix.
        for (int n = 2; n <= taken.size() + 2; n++) {
            String candidate = dirBlocked
                    ? entry.substring(0, first + 1) + "collected~" + n + "/" + entry.substring(first + 1)
                    : stem + "~" + n + ext;
            if (!clashes(candidate, taken)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("No free entry name for '" + entry + "'");
    }

    /** Whether a taken file sits where one of {@code key}'s directories would be. */
    private static boolean fileOnPath(String key, Set<String> taken) {
        for (int i = key.indexOf('/'); i > 0; i = key.indexOf('/', i + 1)) {
            if (taken.contains(key.substring(0, i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean clashes(String entry, Set<String> taken) {
        String key = EntryPaths.collisionKey(entry);
        if (taken.contains(key) || fileOnPath(key, taken)) {
            return true;
        }
        String dir = key + "/";
        for (String t : taken) {
            if (t.startsWith(dir)) {
                return true; // we would be a file where an existing entry needs a directory
            }
        }
        return false;
    }

    private static UiBytes provenance(SbuiArchive sbui) {
        SbuiManifest m = sbui.manifest();
        UiValue.Obj json = new ObjWriter()
                .put("importedFrom", m.assetId())
                .put("sbuiSchemaVersion", m.schemaVersion().toString())
                .put("sourceDigest", m.source().digest())
                .putList("collected", m.dependencies().stream().filter(r -> r.location() == Location.SBUI).toList(),
                        r -> new ObjWriter().put("id", r.id()).put("sha256", r.sha256()).build())
                .build();
        return UiBytes.copyOf(CanonicalJson.write(json));
    }
}
