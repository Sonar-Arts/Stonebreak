package com.openmason.ui.hub.state;

import imgui.type.ImBoolean;

/**
 * UI visibility flags for Hub components.
 * Mirrors the existing UIVisibilityState pattern.
 */
public class HubVisibilityState {

    private final ImBoolean showSidebar = new ImBoolean(true);
    private final ImBoolean showPreviewPanel = new ImBoolean(true);
    private final ImBoolean showTopToolbar = new ImBoolean(true);


//  getters
    public boolean isSidebarVisible() {
        return showSidebar.get();
    }

    public boolean isPreviewPanelVisible() {
        return showPreviewPanel.get();
    }

    public boolean isTopToolbarVisible() {
        return showTopToolbar.get();
    }
}
