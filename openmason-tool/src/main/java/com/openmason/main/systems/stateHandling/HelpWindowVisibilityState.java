package com.openmason.main.systems.stateHandling;

import imgui.type.ImBoolean;

/**
 * Interface for managing help window visibility state.
 * Allows different tools to provide help menu functionality without tight coupling to specific state classes.
 * Follows Interface Segregation Principle by providing only help-related visibility methods.
 *
 * <p>This interface enables the HelpMenuHandler to work with any state management class
 * that can show/hide an about dialog, making it reusable across Model Viewer, Texture Creator,
 * and future tools.</p>
 *
 * @author Open Mason Team
 */
public interface HelpWindowVisibilityState {

    /**
     * Show the about dialog.
     * Implementations should set the about window visibility flag to true.
     */
    void showAbout();

    /**
     * Get the about window visibility flag.
     * Used by AboutDialog to determine if it should render and to bind its close button.
     *
     * @return ImBoolean flag controlling about window visibility
     */
    ImBoolean getShowAboutWindow();
}
