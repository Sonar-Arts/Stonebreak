package com.openmason.engine.ui.assets.edit;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDependency.Mode;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.io.EntryPaths;
import com.openmason.engine.ui.assets.DependencyRefs;
import com.openmason.engine.ui.assets.ProjectAssetSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Relink a shared dependency to another project file, or rename a dependency id across the
 * whole document. Both are how an unresolved reference is repaired: editor saves keep the
 * unresolved row untouched until one of these runs.
 */
public final class RelinkOperations {

    private RelinkOperations() {
    }

    /** Points shared {@code id} at {@code projectPath} and records that file's hash. */
    public static AssetEdit relink(OmuiArchive doc, String id, String projectPath, ProjectAssetSource project)
            throws UiFormatException {
        UiDependency row = DocumentEdits.row(doc, id);
        if (row.mode() != Mode.SHARED) {
            throw DocumentEdits.fail(Code.INVALID_VALUE, "'" + id
                    + "' is an embedded snapshot owned by the document; extract it before relinking");
        }
        String problem = EntryPaths.problem(projectPath);
        if (problem != null) {
            throw DocumentEdits.fail(Code.UNSAFE_ENTRY_PATH, "'" + projectPath + "' is not a portable project path: "
                    + problem);
        }
        UiBytes bytes;
        try {
            bytes = project.folder().read(projectPath);
        } catch (IOException e) {
            throw DocumentEdits.fail(Code.MISSING_ENTRY, "Cannot read project:" + projectPath + ": " + e.getMessage());
        }
        if (bytes == null) {
            throw DocumentEdits.fail(Code.MISSING_ENTRY, "Nothing at project:" + projectPath);
        }
        UiDiagnostics d = new UiDiagnostics();
        d.info(Code.ASSET_RELINKED, OmuiFormat.DEPENDENCIES, "", "Relinked '" + id + "' to project:" + projectPath);
        OmuiArchive after = DocumentEdits.withRows(doc, Map.of(id,
                DocumentEdits.place(row, Mode.SHARED, null, bytes, projectPath)));
        return new AssetEdit("Relink " + id, doc, DocumentEdits.validated(after), List.of(), d.list());
    }

    /**
     * Renames dependency {@code oldId} to {@code newId} in the table and every reference
     * ({@link DependencyRefs#remap}). Lua source is never rewritten; scripts that mention the old
     * id are reported so the author can update them.
     */
    public static AssetEdit rename(OmuiArchive doc, String oldId, String newId) throws UiFormatException {
        DocumentEdits.row(doc, oldId);
        if (!OmuiFormat.LOGICAL_ID.matcher(newId).matches()) {
            throw DocumentEdits.fail(Code.INVALID_ID, "'" + newId + "' is not a logical id");
        }
        if (doc.dependencies().find(newId) != null) {
            throw DocumentEdits.fail(Code.DUPLICATE_ID, "'" + newId + "' is already in the dependency table");
        }
        UiDiagnostics d = new UiDiagnostics();
        d.info(Code.ID_REMAPPED, OmuiFormat.DEPENDENCIES, "", "Renamed '" + oldId + "' to '" + newId + "'");
        reportScripts(doc, Set.of(oldId), d);
        OmuiArchive after = DependencyRefs.remap(doc, Map.of(oldId, newId));
        return new AssetEdit("Rename " + oldId, doc, DocumentEdits.validated(after), List.of(), d.list());
    }

    static void reportScripts(OmuiArchive doc, Set<String> ids, UiDiagnostics d) {
        for (String script : DependencyRefs.scriptsMentioning(doc, ids)) {
            d.warning(Code.ID_REMAPPED, OmuiFormat.scriptEntry(script), "",
                    "Lua source mentions a renamed dependency id; scripts are never rewritten, update it by hand");
        }
    }
}
