package com.openmason.main.systems.stateHandling;

import imgui.type.ImBoolean;

/**
 * Centralized UI visibility state management.
 * Follows Single Responsibility Principle - only manages panel visibility state.
 * Implements HelpWindowVisibilityState to support help menu functionality.
 */
public class UIVisibilityState implements HelpWindowVisibilityState {

    private final ImBoolean showModelBrowser = new ImBoolean(true);

    // Scene Viewer surfaces. showSceneViewer has a flag (unlike the 3D Viewport) so the
    // View menu can hide it, but fullscreen-viewport deliberately leaves it alone —
    // hiding both centre tabs would strand the central dock node empty.
    private final ImBoolean showSceneViewer = new ImBoolean(true);
    private final ImBoolean showSceneOutliner = new ImBoolean(true);
    private final ImBoolean showSceneInspector = new ImBoolean(true);
    private final ImBoolean showPropertyPanel = new ImBoolean(true);
    private final ImBoolean showRiggingPane = new ImBoolean(true);
    private final ImBoolean showToolbar = new ImBoolean(true);
    private final ImBoolean showPreferencesWindow = new ImBoolean(false);
    private final ImBoolean showAboutWindow = new ImBoolean(false);
    private final ImBoolean showSBOExportWindow = new ImBoolean(false);
    private final ImBoolean showSBEExportWindow = new ImBoolean(false);
    private final ImBoolean showSBTExportWindow = new ImBoolean(false);
    private final ImBoolean showSBOTextureExportWindow = new ImBoolean(false);

    // Getters

    public ImBoolean getShowModelBrowser() {
        return showModelBrowser;
    }

    public ImBoolean getShowPropertyPanel() {
        return showPropertyPanel;
    }

    public ImBoolean getShowToolbar() {
        return showToolbar;
    }

    public ImBoolean getShowRiggingPane() {
        return showRiggingPane;
    }

    public ImBoolean getShowPreferencesWindow() {
        return showPreferencesWindow;
    }

    public ImBoolean getShowAboutWindow() {
        return showAboutWindow;
    }

    public ImBoolean getShowSBOExportWindow() {
        return showSBOExportWindow;
    }

    public ImBoolean getShowSBEExportWindow() {
        return showSBEExportWindow;
    }

    public ImBoolean getShowSBTExportWindow() {
        return showSBTExportWindow;
    }

    public ImBoolean getShowSBOTextureExportWindow() {
        return showSBOTextureExportWindow;
    }

    // Toggle methods

    public void toggleModelBrowser() {
        showModelBrowser.set(!showModelBrowser.get());
    }

    public void togglePropertyPanel() {
        showPropertyPanel.set(!showPropertyPanel.get());
    }

    public void toggleRiggingPane() {
        showRiggingPane.set(!showRiggingPane.get());
    }

    public void toggleToolbar() {
        showToolbar.set(!showToolbar.get());
    }

    /**
     * Show the preferences window.
     */
    public void showPreferences() {
        showPreferencesWindow.set(true);
    }

    /**
     * Show the about window.
     */
    public void showAbout() {
        showAboutWindow.set(true);
    }

    /**
     * Reset to default layout visibility.
     */
    public void resetToDefault() {
        showModelBrowser.set(true);
        showPropertyPanel.set(true);
        showRiggingPane.set(true);
        showToolbar.set(true);
        showPreferencesWindow.set(false);
        showAboutWindow.set(false);
        showSBOExportWindow.set(false);
        showSBEExportWindow.set(false);
        showSBTExportWindow.set(false);
        showSBOTextureExportWindow.set(false);
    }

    /**
     * Toggle fullscreen viewport mode (hide/show all panels).
     */
    public void toggleFullscreenViewport() {
        if (showModelBrowser.get() || showPropertyPanel.get() || showRiggingPane.get()) {
            // Hide panels for fullscreen
            showModelBrowser.set(false);
            showPropertyPanel.set(false);
            showRiggingPane.set(false);
            showToolbar.set(false);
        } else {
            // Restore panels
            showModelBrowser.set(true);
            showPropertyPanel.set(true);
            showRiggingPane.set(true);
            showToolbar.set(true);
        }
    }

    public ImBoolean getShowSceneViewer() { return showSceneViewer; }
    public ImBoolean getShowSceneOutliner() { return showSceneOutliner; }
    public ImBoolean getShowSceneInspector() { return showSceneInspector; }

    public void toggleSceneViewer() { showSceneViewer.set(!showSceneViewer.get()); }
    public void toggleSceneOutliner() { showSceneOutliner.set(!showSceneOutliner.get()); }
    public void toggleSceneInspector() { showSceneInspector.set(!showSceneInspector.get()); }
}
