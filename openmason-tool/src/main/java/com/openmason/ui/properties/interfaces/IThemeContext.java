package com.openmason.ui.properties.interfaces;

import com.openmason.ui.themes.core.ThemeDefinition;

/**
 * Interface for theme context abstraction following SOLID principles.
 * Provides an abstraction layer for theme-aware styling.
 */
public interface IThemeContext {

    /**
     * Check if the theme system is available.
     *
     * @return true if theme system is available
     */
    boolean isAvailable();

    /**
     * Get the current theme definition.
     *
     * @return The current theme, or null if theme system is not available
     */
    ThemeDefinition getCurrentTheme();

    /**
     * Apply panel-specific theme styling.
     * Must be called before rendering themed components.
     */
    void applyPanelStyle();

    /**
     * Restore default styling after theme-aware rendering.
     * Must be called after rendering themed components to clean up.
     */
    void restorePanelStyle();

    /**
     * Render status text with theme-aware colors.
     *
     * @param statusMessage The status message to render
     * @param isLoading Is a loading operation in progress
     * @param isValidating Is a validation operation in progress
     */
    void renderStatusText(String statusMessage, boolean isLoading, boolean isValidating);
}
