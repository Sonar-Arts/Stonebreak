package com.openmason.main.systems.menus.panes.projectBrowser.events;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;

/** A {@code .omsc} was clicked in the Project Browser. */
public record SceneSelectedEvent(AssetEntry entry) {
}
