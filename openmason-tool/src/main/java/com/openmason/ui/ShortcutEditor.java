package com.openmason.ui;

import java.util.List;

/**
 * Interface for shortcut editor implementations.
 * Defines the contract for shortcut editing functionality.
 */
public interface ShortcutEditor {
    
    /**
     * Show the shortcut editor
     */
    void show();
    
    /**
     * Hide the shortcut editor
     */
    void hide();
    
    /**
     * Render the shortcut editor UI
     */
    void render();
    
    /**
     * Check if the editor is currently showing
     * @return true if showing, false otherwise
     */
    boolean isShowing();
    
    /**
     * Check if there are unsaved changes
     * @return true if there are unsaved changes
     */
    boolean hasUnsavedChanges();
    
    /**
     * Get the number of shortcuts
     * @return shortcut count
     */
    int getShortcutCount();
    
    /**
     * Get the number of conflicts
     * @return conflict count
     */
    int getConflictCount();
    
    /**
     * Get current status message
     * @return status message
     */
    String getStatusMessage();
    
    /**
     * Clean up resources
     */
    void dispose();
    
    /**
     * Get debug information about the editor state
     * @return debug info string
     */
    String getDebugInfo();
}