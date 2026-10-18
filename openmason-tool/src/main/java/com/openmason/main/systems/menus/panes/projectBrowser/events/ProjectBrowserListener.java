package com.openmason.main.systems.menus.panes.projectBrowser.events;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;

/**
 * Listener for Project Browser events. Opening a selected file is its file type's registered
 * opener (#379); listeners hear model loads and delete requests.
 */
public interface ProjectBrowserListener {

    /** A .OMO model was selected (the viewport load is handled by the controller). */
    void onModelSelected(ModelSelectedEvent event);

    /** Delete was chosen for an asset; the shell shows the confirmation dialog. */
    void onAssetDeleteRequested(AssetEntry entry);
}
