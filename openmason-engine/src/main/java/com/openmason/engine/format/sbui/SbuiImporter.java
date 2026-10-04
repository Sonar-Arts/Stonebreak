package com.openmason.engine.format.sbui;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.CanonicalJson;
import com.openmason.engine.format.omui.io.ObjWriter;
import com.openmason.engine.format.sbui.SbuiManifest.Location;
import com.openmason.engine.format.sbui.SbuiManifest.SbuiDependency;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     */
    public static Imported importPortable(SbuiArchive sbui) {
        Imported plain = importEditable(sbui);
        OmuiArchive doc = plain.document();
        List<UiDependency> rows = new ArrayList<>();
        for (UiDependency dep : doc.dependencies().entries()) {
            UiBytes bytes = plain.collected().get(dep.id());
            if (bytes == null) {
                rows.add(dep);
                continue;
            }
            String entry = sbui.manifest().dependency(dep.id()).entry(); // already assets/...
            doc = doc.withAsset(entry, bytes);
            rows.add(new UiDependency(dep.id(), dep.kind(), dep.version(), bytes.sha256(), bytes.size(),
                    UiDependency.Mode.EMBEDDED, entry, dep.sourceHint(), dep.requires(), dep.optional(), dep.fallback(),
                    dep.license(), dep.unknown()));
        }
        doc = doc.withDependencies(new OmuiArchive.UiDependencies(rows, doc.dependencies().unknown()));
        return new Imported(doc, plain.collected());
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
