package com.openmason.main.systems.menus.panes.projectBrowser.events;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;

/** Event fired when a .OMT texture is selected in the Project Browser. */
public record TextureSelectedEvent(AssetEntry entry, long timestamp) {
    public TextureSelectedEvent(AssetEntry entry) {
        this(entry, System.currentTimeMillis());
    }
}
