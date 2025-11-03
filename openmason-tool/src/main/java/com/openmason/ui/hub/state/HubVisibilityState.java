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

    // Getters for ImBoolean references (allows direct binding to ImGui)

    public ImBoolean getShowSidebar() {
        return showSidebar;
    }

    public ImBoolean getShowPreviewPanel() {
        return showPreviewPanel;
    }

    public ImBoolean getShowTopToolbar() {
        return showTopToolbar;
    }

    // Convenience methods

    public void setSidebarVisible(boolean visible) {
        showSidebar.set(visible);
    }

    public void setPreviewPanelVisible(boolean visible) {
        showPreviewPanel.set(visible);
    }

    public void setTopToolbarVisible(boolean visible) {
        showTopToolbar.set(visible);
    }

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
