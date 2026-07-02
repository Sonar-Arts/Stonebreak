package com.openmason.main.systems.menus.panes.projectBrowser.events;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;

/** Event fired when a .OMO model is selected in the Project Browser. */
public record ModelSelectedEvent(AssetEntry entry, long timestamp) {
    public ModelSelectedEvent(AssetEntry entry) {
        this(entry, System.currentTimeMillis());
    }
}
