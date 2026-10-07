package com.openmason.engine.ui.assets.edit;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDependency.Mode;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiImporter;
import com.openmason.engine.ui.assets.DependencyRefs;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.ResolvedAsset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Imports an SBUI into a project, turning the assets the export collected into shared project
 * assets. Per collected dependency:
 * <ul>
 *   <li>the project has nothing under that id → the bytes are written (hint path, else
 *       convention path);</li>
 *   <li>the project has identical bytes → reused, nothing written;</li>
 *   <li>the project has different bytes → the imported copy gets a fresh id
 *       ({@code <id>-imported}, {@code <id>-imported-2}, ...) and every reference in the
 *       document is remapped. Existing project assets are never overwritten.</li>
 * </ul>
 * For a document that should stay self-contained instead, use
 * {@link SbuiImporter#importPortable}.
 */
public final class SbuiProjectImport {

    /**
     * @param edit     the imported document and the project writes ({@link AssetEdit#apply})
     * @param remapped original id → id the imported copy was given
     */
    public record Result(AssetEdit edit, Map<String, String> remapped) {
    }

    private SbuiProjectImport() {
    }

    public static Result importIntoProject(SbuiArchive sbui, ProjectAssetSource project) throws UiFormatException {
        SbuiImporter.Imported imported = SbuiImporter.importEditable(sbui);
        OmuiArchive doc = imported.document();
        UiDiagnostics d = new UiDiagnostics();
        List<ProjectWrite> writes = new ArrayList<>();
        Map<String, String> renames = new TreeMap<>(UiValue.KEY_ORDER);
        Map<String, UiDependency> placed = new HashMap<>();
        Map<String, UiBytes> collected = new TreeMap<>(UiValue.KEY_ORDER);
        collected.putAll(imported.collected());

        for (Map.Entry<String, UiBytes> c : collected.entrySet()) {
            UiDependency row = DocumentEdits.row(doc, c.getKey());
            UiBytes bytes = c.getValue();
            ResolvedAsset existing = find(project, row.id(), row, row.sourceHint());
            String path;
            if (existing != null && existing.bytes().equals(bytes)) {
                path = existing.location();
                d.info(Code.ASSET_RELINKED, OmuiFormat.DEPENDENCIES, "", "Reused project:" + path + " for '" + row.id() + "'");
            } else {
                String id = row.id();
                ResolvedAsset there = null;
                if (existing == null) {
                    path = project.importPlacementFor(id, row.kind(), row.sourceHint());
                } else {
                    id = freshId(doc, project, row, bytes);
                    renames.put(row.id(), id);
                    there = find(project, id, row, null);
                    path = there != null ? there.location() : project.conventionPath(id, row.kind(), row.sourceHint());
                    d.warning(Code.ID_REMAPPED, OmuiFormat.DEPENDENCIES, "", "project:" + existing.location()
                            + " already holds '" + row.id() + "' with different content; imported as '" + id + "'");
                }
                if (there == null) {
                    writes.add(new ProjectWrite(path, null, bytes));
                    d.info(Code.ASSET_EXTRACTED, OmuiFormat.DEPENDENCIES, "", "Imported '" + id + "' to project:" + path);
                }
            }
            placed.put(row.id(), DocumentEdits.place(row, Mode.SHARED, null, bytes, path));
        }
        OmuiArchive after = DocumentEdits.withRows(doc, placed);
        RelinkOperations.reportScripts(after, renames.keySet(), d);
        after = DependencyRefs.remap(after, renames);
        componentIdClash(after, project, d);
        return new Result(new AssetEdit("Import " + sbui.manifest().assetId(), doc, DocumentEdits.validated(after),
                writes, d.list()), new LinkedHashMap<>(renames));
    }

    /** {@code <id>-imported[-n]}: not in the table and either free in the project or holding these bytes. */
    private static String freshId(OmuiArchive doc, ProjectAssetSource project, UiDependency row, UiBytes bytes)
            throws UiFormatException {
        for (int n = 1; n <= OmuiFormat.MAX_DEPENDENCIES; n++) {
            String candidate = row.id() + "-imported" + (n == 1 ? "" : "-" + n);
            if (!OmuiFormat.LOGICAL_ID.matcher(candidate).matches()) {
                break;
            }
            if (doc.dependencies().find(candidate) != null) {
                continue;
            }
            ResolvedAsset there = find(project, candidate, row, null);
            if (there == null || there.bytes().equals(bytes)) {
                return candidate;
            }
        }
        throw DocumentEdits.fail(Code.DUPLICATE_ID, "No free id to import '" + row.id() + "' under");
    }

    /**
     * The project's copy of {@code id}. A remapped id is looked up by convention only: the
     * row's hint names the original id's file.
     */
    private static ResolvedAsset find(ProjectAssetSource project, String id, UiDependency row, String hint)
            throws UiFormatException {
        try {
            return project.find(id, row.kind(), hint);
        } catch (IOException e) {
            throw DocumentEdits.fail(Code.MISSING_ENTRY, "Cannot read the project copy of '" + id + "': " + e.getMessage());
        }
    }

    private static void componentIdClash(OmuiArchive doc, ProjectAssetSource project, UiDiagnostics d) {
        String id = doc.manifest().documentId();
        try {
            ResolvedAsset there = project.find(id, UiDependency.Kind.COMPONENT, null);
            if (there != null) {
                d.warning(Code.DUPLICATE_ID, OmuiFormat.MANIFEST, "/documentId", "project:" + there.location()
                        + " already uses document id '" + id + "'; save the import under a new id or replace it");
            }
        } catch (IOException ignored) {
            // advisory only
        }
    }
}
