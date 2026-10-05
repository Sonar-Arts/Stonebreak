package com.openmason.engine.ui.assets.edit;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDependency.Mode;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.ui.assets.AssetKinds;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.DependencyRefs;
import com.openmason.engine.ui.assets.ResolvedAsset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embed and refresh-embedded-copy. Both snapshot a dependency <em>and its required
 * closure</em> ({@code requires}, transitively), so an embedded component never points at a
 * shared texture the project might not have.
 */
public final class EmbedOperations {

    private EmbedOperations() {
    }

    /**
     * Turns {@code id} and the shared members of its closure into snapshots inside the
     * document. A required member that cannot be found blocks the command; an optional one
     * stays shared with a warning. Snapshots keep their {@code sourceHint} so refresh and
     * extract know where the original lives.
     */
    public static AssetEdit embed(OmuiArchive doc, String id, List<? extends AssetSource> sources)
            throws UiFormatException {
        DocumentEdits.row(doc, id);
        UiDiagnostics d = new UiDiagnostics();
        Map<String, UiBytes> assets = new LinkedHashMap<>(doc.assets());
        Map<String, UiDependency> replaced = new LinkedHashMap<>();
        for (String member : DependencyRefs.closure(doc, id)) {
            UiDependency row = doc.dependencies().find(member);
            if (row.mode() == Mode.EMBEDDED) {
                continue;
            }
            ResolvedAsset found = DocumentEdits.findShared(sources, row);
            if (found == null) {
                if (!row.optional() || member.equals(id)) {
                    throw DocumentEdits.fail(Code.MISSING_ENTRY, "Cannot embed '" + member + "': no shared source has it"
                            + (member.equals(id) ? "" : " (required by '" + id + "')"));
                }
                d.warning(Code.MISSING_ENTRY, OmuiFormat.DEPENDENCIES, "", "Optional '" + member
                        + "' was not found and stays shared");
                continue;
            }
            String wanted = OmuiFormat.ASSETS_DIR + AssetKinds.fileName(member, row.kind(), row.sourceHint());
            String entry = DocumentEdits.uniqueEntry(assets, wanted, found.bytes());
            if (!entry.equals(wanted)) {
                d.info(Code.ENTRY_RENAMED, entry, "", "'" + wanted + "' is taken; snapshot of '" + member
                        + "' stored as '" + entry + "'");
            }
            assets.put(entry, found.bytes());
            replaced.put(member, DocumentEdits.place(row, Mode.EMBEDDED, entry, found.bytes(), row.sourceHint()));
            d.info(Code.ASSET_EMBEDDED, entry, "", "Embedded '" + member + "' from " + found.describe());
        }
        OmuiArchive after = withAssets(DocumentEdits.withRows(doc, replaced), assets);
        return new AssetEdit("Embed " + id, doc, DocumentEdits.validated(after), List.of(), d.list());
    }

    /**
     * Re-snapshots {@code id} and the embedded members of its closure from their shared
     * originals. Unchanged snapshots are left alone; a member without a reachable original
     * keeps its snapshot (warning). Explicit by design: nothing refreshes snapshots implicitly.
     */
    public static AssetEdit refresh(OmuiArchive doc, String id, List<? extends AssetSource> sources)
            throws UiFormatException {
        UiDependency target = DocumentEdits.row(doc, id);
        if (target.mode() != Mode.EMBEDDED) {
            throw DocumentEdits.fail(Code.INVALID_VALUE, "'" + id + "' is shared; only embedded snapshots refresh");
        }
        UiDiagnostics d = new UiDiagnostics();
        OmuiArchive after = doc;
        Map<String, UiBytes> assets = new LinkedHashMap<>(doc.assets());
        Map<String, UiDependency> replaced = new LinkedHashMap<>();
        for (String member : DependencyRefs.closure(doc, id)) {
            UiDependency row = doc.dependencies().find(member);
            if (row.mode() != Mode.EMBEDDED) {
                continue;
            }
            ResolvedAsset found = DocumentEdits.findShared(sources, row);
            if (found == null) {
                d.warning(Code.MISSING_ENTRY, OmuiFormat.DEPENDENCIES, "", "No shared original of '" + member
                        + "' was found; its snapshot is kept");
                continue;
            }
            if (found.sha256().equals(row.sha256())) {
                continue;
            }
            boolean sharedEntry = doc.dependencies().entries().stream()
                    .anyMatch(o -> !o.id().equals(member) && row.entry().equals(o.entry()));
            String entry = row.entry();
            if (sharedEntry) {
                entry = DocumentEdits.uniqueEntry(assets, row.entry(), found.bytes());
            }
            assets.put(entry, found.bytes());
            replaced.put(member, DocumentEdits.place(row, Mode.EMBEDDED, entry, found.bytes(), row.sourceHint()));
            d.info(Code.ASSET_REFRESHED, entry, "", "Refreshed '" + member + "' from " + found.describe());
        }
        after = withAssets(DocumentEdits.withRows(after, replaced), assets);
        return new AssetEdit("Refresh embedded " + id, doc, DocumentEdits.validated(after), List.of(), d.list());
    }

    private static OmuiArchive withAssets(OmuiArchive doc, Map<String, UiBytes> assets) {
        return new OmuiArchive(doc.manifest(), doc.document(), doc.styles(), doc.graphs(), doc.animations(),
                doc.scripts(), doc.dependencies(), assets, doc.editor(), doc.extraEntries());
    }
}
