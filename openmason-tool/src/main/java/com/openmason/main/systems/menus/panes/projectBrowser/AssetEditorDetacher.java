package com.openmason.main.systems.menus.panes.projectBrowser;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorState;
import com.openmason.main.systems.stateHandling.ModelState;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Detaches the editor from a deleted backing file so the next Save behaves
 * like Save As (never quietly recreates the deleted file). Stateless; takes
 * the editors' states per call so the rules are testable without a window.
 */
public class AssetEditorDetacher {

    /**
     * Resolve the detach decisions after a delete. No-op when the delete failed —
     * the editor stays attached to a file that still exists.
     */
    public void afterDelete(AssetEntry entry, boolean deleted, ModelState modelState, TextureCreatorState textureState) {
        if (!deleted) {
            return;  // delete failed; editor stays attached
        }
        switch (entry.type()) {
            case OMO -> detachModel(entry, modelState);
            case OMT -> detachTexture(entry, textureState);
            case OMSC -> { }
        }
    }

    /**
     * Blank path -> {@code hasOMOFile()} false -> {@code saveModel()} routes to
     * Save As; the in-memory copy stays editable and is flagged unsaved
     * (precedent: ModelOperationService's imported-asset copy) so quitting or
     * switching models prompts to save the only remaining copy.
     */
    private void detachModel(AssetEntry entry, ModelState modelState) {
        if (modelState == null) {
            return;
        }
        if (pathsEqual(entry.path(), modelState.getCurrentOMOFilePath())) {
            modelState.setCurrentOMOFilePath(null);
            modelState.setUnsavedChanges(true);
        }
    }

    /**
     * {@code hasFilePath()}/{@code isProjectFile()} false -> {@code FileOperationsCoordinator.saveProject}
     * routes to Save Project As; canvas/layers stay editable and {@code markAsModified()}
     * flags the only remaining copy unsaved so closing prompts to save it.
     */
    private void detachTexture(AssetEntry entry, TextureCreatorState textureState) {
        if (textureState == null) {
            return;
        }
        if (pathsEqual(entry.path(), textureState.getCurrentFilePath())) {
            textureState.setCurrentFilePath(null);
            textureState.setIsProjectFile(false);
            textureState.markAsModified();
        }
    }

    /** Normalized comparison; the backing path may come from a different resolution (project restore) than the scanner's. */
    static boolean pathsEqual(Path p, String backing) {
        if (backing == null || backing.isBlank()) {
            return false;
        }
        try {
            return p.normalize().equals(Path.of(backing).normalize());
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
