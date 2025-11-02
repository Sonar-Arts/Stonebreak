package com.openmason.ui.components.textureCreator;

import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages visibility state for all texture creator windows and panels.
 *
 * This class centralizes window visibility management using ImBoolean references
 * that can be directly passed to ImGui.begin() calls. When a user clicks the (x)
 * button on a window, ImGui automatically updates these ImBoolean flags.
 *
 * Windows are categorized as:
 * - Core panels (Tools, Canvas) - Always visible, not closeable
 * - Closeable panels (Layers, Color) - Can be closed and reopened via menu
 * - Dialog windows (Preferences, Noise Filter) - Floating windows that can be closed
 *
 * @author Open Mason Team
 */
public class TextureCreatorWindowState {

    private static final Logger logger = LoggerFactory.getLogger(TextureCreatorWindowState.class);

    // ========================================
    // CLOSEABLE PANELS
    // ========================================

    /**
     * Layers panel visibility.
     * Shows layer stack, reordering, visibility toggles, and layer operations.
     */
    private final ImBoolean showLayersPanel = new ImBoolean(true);

    /**
     * Color picker panel visibility.
     * Shows color picker, color history, and current color display.
     */
    private final ImBoolean showColorPanel = new ImBoolean(true);

    // ========================================
    // DIALOG WINDOWS
    // ========================================

    /**
     * Preferences window visibility.
     * Shows texture creator settings like grid opacity, cube net overlay, etc.
     */
    private final ImBoolean showPreferencesWindow = new ImBoolean(false);

    /**
     * Noise filter window visibility.
     * Shows noise generation controls and preview.
     */
    private final ImBoolean showNoiseFilterWindow = new ImBoolean(false);

    /**
     * Create window state manager with default visibility settings.
     */
    public TextureCreatorWindowState() {
        logger.debug("Texture creator window state initialized");
    }

    // ========================================
    // CLOSEABLE PANELS - GETTERS
    // ========================================

    /**
     * Get layers panel visibility flag.
     * @return ImBoolean reference for ImGui binding
     */
    public ImBoolean getShowLayersPanel() {
        return showLayersPanel;
    }

    /**
     * Get color panel visibility flag.
     * @return ImBoolean reference for ImGui binding
     */
    public ImBoolean getShowColorPanel() {
        return showColorPanel;
    }

    // ========================================
    // DIALOG WINDOWS - GETTERS
    // ========================================

    /**
     * Get preferences window visibility flag.
     * @return ImBoolean reference for ImGui binding
     */
    public ImBoolean getShowPreferencesWindow() {
        return showPreferencesWindow;
    }

    /**
     * Get noise filter window visibility flag.
     * @return ImBoolean reference for ImGui binding
     */
    public ImBoolean getShowNoiseFilterWindow() {
        return showNoiseFilterWindow;
    }

    // ========================================
    // TOGGLE METHODS
    // ========================================

    /**
     * Toggle layers panel visibility.
     */
    public void toggleLayersPanel() {
        showLayersPanel.set(!showLayersPanel.get());
        logger.debug("Layers panel visibility toggled to: {}", showLayersPanel.get());
    }

    /**
     * Toggle color panel visibility.
     */
    public void toggleColorPanel() {
        showColorPanel.set(!showColorPanel.get());
        logger.debug("Color panel visibility toggled to: {}", showColorPanel.get());
    }

    /**
     * Toggle preferences window visibility.
     */
    public void togglePreferencesWindow() {
        showPreferencesWindow.set(!showPreferencesWindow.get());
        logger.debug("Preferences window visibility toggled to: {}", showPreferencesWindow.get());
    }

    /**
     * Toggle noise filter window visibility.
     */
    public void toggleNoiseFilterWindow() {
        showNoiseFilterWindow.set(!showNoiseFilterWindow.get());
        logger.debug("Noise filter window visibility toggled to: {}", showNoiseFilterWindow.get());
    }

    // ========================================
    // SHOW METHODS (for explicit opening)
    // ========================================

    /**
     * Show preferences window (if currently hidden).
     */
    public void showPreferencesWindow() {
        if (!showPreferencesWindow.get()) {
            showPreferencesWindow.set(true);
            logger.debug("Preferences window opened");
        }
    }

    /**
     * Show noise filter window (if currently hidden).
     */
    public void showNoiseFilterWindow() {
        if (!showNoiseFilterWindow.get()) {
            showNoiseFilterWindow.set(true);
            logger.debug("Noise filter window opened");
        }
    }

    /**
     * Show layers panel (if currently hidden).
     */
    public void showLayersPanel() {
        if (!showLayersPanel.get()) {
            showLayersPanel.set(true);
            logger.debug("Layers panel opened");
        }
    }

    /**
     * Show color panel (if currently hidden).
     */
    public void showColorPanel() {
        if (!showColorPanel.get()) {
            showColorPanel.set(true);
            logger.debug("Color panel opened");
        }
    }

    // ========================================
    // UTILITY METHODS
    // ========================================

    /**
     * Reset all panels to default visibility.
     * Core panels (Tools, Canvas) are always visible.
     * Closeable panels (Layers, Color) are shown by default.
     * Dialog windows (Preferences, Noise) are hidden by default.
     */
    public void resetToDefault() {
        showLayersPanel.set(true);
        showColorPanel.set(true);
        showPreferencesWindow.set(false);
        showNoiseFilterWindow.set(false);
        logger.info("Window visibility reset to defaults");
    }

    /**
     * Check if any panel is hidden (useful for "show all" functionality).
     * @return true if any closeable panel is hidden
     */
    public boolean hasHiddenPanels() {
        return !showLayersPanel.get() || !showColorPanel.get();
    }

    /**
     * Show all closeable panels (does not affect dialog windows).
     */
    public void showAllPanels() {
        showLayersPanel.set(true);
        showColorPanel.set(true);
        logger.info("All panels shown");
    }
}
