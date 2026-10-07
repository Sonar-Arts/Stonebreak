package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency.Kind;
import com.openmason.engine.format.omui.io.EntryPaths;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Shared assets of an Open Mason project (editor only). Lookup order, first hit wins:
 * <ol>
 *   <li>the row's {@code sourceHint}, a project-relative path, so a moved or copied project
 *       resolves without repair;</li>
 *   <li>the convention path {@code <conventionDir><namespace>/<path><ext>}
 *       ({@link AssetKinds#candidates}), where extract-to-project and import place files.</li>
 * </ol>
 */
public final class ProjectAssetSource implements AssetSource {

    public static final String NAME = "project";

    private final ProjectFolder folder;
    private final String conventionDir;

    /**
     * @param conventionDir project-relative folder of convention-placed assets, {@code ""} or
     *                      ending in {@code /} ({@code UI/})
     */
    public ProjectAssetSource(ProjectFolder folder, String conventionDir) {
        this.folder = Objects.requireNonNull(folder, "folder");
        this.conventionDir = conventionDir == null ? "" : conventionDir;
        if (!this.conventionDir.isEmpty() && !this.conventionDir.endsWith("/")) {
            throw new IllegalArgumentException("conventionDir must end with '/': " + conventionDir);
        }
    }

    public ProjectFolder folder() {
        return folder;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AssetOrigin origin() {
        return AssetOrigin.PROJECT;
    }

    @Override
    public ResolvedAsset find(String id, Kind kind, String sourceHint) throws IOException {
        if (sourceHint != null && EntryPaths.problem(sourceHint) == null) {
            UiBytes bytes = folder.read(sourceHint);
            if (bytes != null) {
                return new ResolvedAsset(id, kind, bytes, origin(), NAME, sourceHint);
            }
        }
        for (String rel : conventionCandidates(id, kind)) {
            UiBytes bytes = folder.read(rel);
            if (bytes != null) {
                return new ResolvedAsset(id, kind, bytes, origin(), NAME, rel);
            }
        }
        return null;
    }

    /** Where a new project copy of {@code id} goes: its hint when usable, else the convention path. */
    public String placementFor(String id, Kind kind, String sourceHint) {
        if (sourceHint != null && EntryPaths.problem(sourceHint) == null) {
            return sourceHint;
        }
        return conventionPath(id, kind, sourceHint);
    }

    /**
     * Where an <em>imported</em> copy of {@code id} goes. The hint comes from someone else's
     * archive, so it is honoured only when it lies under the convention folder ({@code UI/});
     * anything else (a path among models, scripts or project settings) lands at the convention
     * path instead, so an import can never place files outside the UI asset folder.
     */
    public String importPlacementFor(String id, Kind kind, String sourceHint) {
        if (sourceHint != null && EntryPaths.problem(sourceHint) == null && sourceHint.startsWith(conventionDir)) {
            return sourceHint;
        }
        return conventionPath(id, kind, sourceHint);
    }

    /** Every convention path {@link #find} tries for {@code id}, in order. */
    public List<String> conventionCandidates(String id, Kind kind) {
        return AssetKinds.candidates(id, kind).stream().map(c -> conventionDir + c).toList();
    }

    public String conventionPath(String id, Kind kind, String sourceHint) {
        return conventionDir + AssetKinds.fileName(id, kind, sourceHint);
    }
}
