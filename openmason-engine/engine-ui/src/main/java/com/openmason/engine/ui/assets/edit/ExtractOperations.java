package com.openmason.engine.ui.assets.edit;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDependency.Mode;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.ResolvedAsset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Extract-to-project: an embedded snapshot becomes a shared project asset. */
public final class ExtractOperations {

    private ExtractOperations() {
    }

    /**
     * Writes the snapshot of {@code id} into the project (at its {@code sourceHint}, else the
     * convention path) and turns the row shared. When the project already holds the id with
     * different bytes, {@code policy} decides; identical bytes simply relink. The snapshot
     * entry is removed unless another row uses it.
     */
    public static AssetEdit extractToProject(OmuiArchive doc, String id, ProjectAssetSource project,
                                             CollisionPolicy policy) throws UiFormatException {
        UiDependency row = DocumentEdits.row(doc, id);
        if (row.mode() != Mode.EMBEDDED) {
            throw DocumentEdits.fail(Code.INVALID_VALUE, "'" + id + "' is already shared");
        }
        UiBytes snapshot = doc.assets().get(row.entry());
        if (snapshot == null) {
            throw DocumentEdits.fail(Code.MISSING_ENTRY, "Snapshot '" + row.entry() + "' of '" + id + "' is missing");
        }
        ResolvedAsset existing;
        try {
            existing = project.find(id, row.kind(), row.sourceHint());
        } catch (IOException e) {
            throw DocumentEdits.fail(Code.MISSING_ENTRY, "Cannot read the project copy of '" + id + "': " + e.getMessage());
        }
        UiDiagnostics d = new UiDiagnostics();
        List<ProjectWrite> writes = new ArrayList<>();
        String path;
        UiBytes linked = snapshot;
        if (existing == null) {
            path = project.placementFor(id, row.kind(), row.sourceHint());
            writes.add(new ProjectWrite(path, null, snapshot));
            d.info(Code.ASSET_EXTRACTED, OmuiFormat.DEPENDENCIES, "", "Extracted '" + id + "' to project:" + path);
        } else {
            path = existing.location();
            if (existing.bytes().equals(snapshot)) {
                d.info(Code.ASSET_RELINKED, OmuiFormat.DEPENDENCIES, "", "project:" + path
                        + " already holds the snapshot of '" + id + "'; linked to it");
            } else {
                switch (policy) {
                    case FAIL -> throw DocumentEdits.fail(Code.DUPLICATE_ID, "project:" + path + " already holds '" + id
                            + "' with different content; choose keep-project or replace");
                    case KEEP_PROJECT -> {
                        linked = existing.bytes();
                        d.warning(Code.ASSET_RELINKED, OmuiFormat.DEPENDENCIES, "", "Linked '" + id + "' to project:"
                                + path + "; the differing snapshot was dropped (undo restores it)");
                    }
                    case REPLACE -> {
                        writes.add(new ProjectWrite(path, existing.bytes(), snapshot));
                        d.warning(Code.ASSET_EXTRACTED, OmuiFormat.DEPENDENCIES, "", "Replaced project:" + path
                                + " with the snapshot of '" + id + "' (undo restores the old file)");
                    }
                }
            }
        }
        OmuiArchive after = DocumentEdits.withRows(doc, Map.of(id,
                DocumentEdits.place(row, Mode.SHARED, null, linked, path)));
        after = DocumentEdits.withoutAssetIfUnused(after, row.entry());
        return new AssetEdit("Extract " + id + " to project", doc, DocumentEdits.validated(after), writes, d.list());
    }
}
