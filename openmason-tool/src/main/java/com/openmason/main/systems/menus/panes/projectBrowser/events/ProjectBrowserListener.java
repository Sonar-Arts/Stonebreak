package com.openmason.main.systems.menus.panes.projectBrowser.events;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;

/**
 * Listener for Project Browser selections — .OMO models and .OMT textures
 * discovered in the open project's folder.
 */
public interface ProjectBrowserListener {

    /** A .OMO model was selected (the viewport load is handled by the controller). */
    void onModelSelected(ModelSelectedEvent event);

    /** A .OMT texture was selected. */
    void onTextureSelected(TextureSelectedEvent event);

    /**
     * A scene was selected. Opening it is the shell's job, not the controller's — it has
     * to run the unsaved-scene check first.
     */
    void onSceneSelected(SceneSelectedEvent event);

    /** Delete was chosen for an asset; the shell shows the confirmation dialog. */
    void onAssetDeleteRequested(AssetEntry entry);
}
