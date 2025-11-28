package com.openmason.ui.state;

import imgui.type.ImBoolean;

/**
 * Centralized UI visibility state management.
 * Follows Single Responsibility Principle - only manages panel visibility state.
 * Implements HelpWindowVisibilityState to support help menu functionality.
 */
public class UIVisibilityState implements HelpWindowVisibilityState {

    private final ImBoolean showModelBrowser = new ImBoolean(true);
    private final ImBoolean showPropertyPanel = new ImBoolean(true);
    private final ImBoolean showToolbar = new ImBoolean(true);
    private final ImBoolean showPreferencesWindow = new ImBoolean(false);
    private final ImBoolean showAboutWindow = new ImBoolean(false);

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

    public ImBoolean getShowPreferencesWindow() {
        return showPreferencesWindow;
    }

    public ImBoolean getShowAboutWindow() {
        return showAboutWindow;
    }

    // Toggle methods

    public void toggleModelBrowser() {
        showModelBrowser.set(!showModelBrowser.get());
    }

    public void togglePropertyPanel() {
        showPropertyPanel.set(!showPropertyPanel.get());
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
        showToolbar.set(true);
        showPreferencesWindow.set(false);
        showAboutWindow.set(false);
    }

    /**
     * Toggle fullscreen viewport mode (hide/show all panels).
     */
    public void toggleFullscreenViewport() {
        if (showModelBrowser.get() || showPropertyPanel.get()) {
            // Hide panels for fullscreen
            showModelBrowser.set(false);
            showPropertyPanel.set(false);
            showToolbar.set(false);
        } else {
            // Restore panels
            showModelBrowser.set(true);
            showPropertyPanel.set(true);
            showToolbar.set(true);
        }
    }
}
