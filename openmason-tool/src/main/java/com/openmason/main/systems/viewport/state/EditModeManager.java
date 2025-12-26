package com.openmason.main.systems.viewport.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton manager for the current editing mode.
 * Controls which geometry type (vertex, edge, face) can be selected and edited.
 * Follows Single Responsibility Principle - only manages edit mode state.
 */
public final class EditModeManager {

    private static final Logger logger = LoggerFactory.getLogger(EditModeManager.class);

    private static final EditModeManager INSTANCE = new EditModeManager();

    private EditMode currentMode = EditMode.NONE;

    private EditModeManager() {
    }

    /**
     * Gets the singleton instance.
     *
     * @return The EditModeManager instance
     */
    public static EditModeManager getInstance() {
        return INSTANCE;
    }

    /**
     * Gets the current editing mode.
     *
     * @return The current EditMode
     */
    public EditMode getCurrentMode() {
        return currentMode;
    }

    /**
     * Cycles to the next editing mode: NONE -> VERTEX -> EDGE -> FACE -> NONE
     */
    public void cycleMode() {
        EditMode previousMode = currentMode;
        currentMode = currentMode.next();
        logger.debug("Edit mode changed: {} -> {}", previousMode.getDisplayName(), currentMode.getDisplayName());
    }

    /**
     * Sets the editing mode directly.
     *
     * @param mode The mode to set
     */
    public void setMode(EditMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("EditMode cannot be null");
        }
        if (currentMode != mode) {
            EditMode previousMode = currentMode;
            currentMode = mode;
            logger.debug("Edit mode set: {} -> {}", previousMode.getDisplayName(), currentMode.getDisplayName());
        }
    }

    /**
     * Checks if editing is allowed for vertices.
     *
     * @return true if current mode is VERTEX
     */
    public boolean isVertexEditingAllowed() {
        return currentMode == EditMode.VERTEX;
    }

    /**
     * Checks if editing is allowed for edges.
     *
     * @return true if current mode is EDGE
     */
    public boolean isEdgeEditingAllowed() {
        return currentMode == EditMode.EDGE;
    }

    /**
     * Checks if editing is allowed for faces.
     *
     * @return true if current mode is FACE
     */
    public boolean isFaceEditingAllowed() {
        return currentMode == EditMode.FACE;
    }

    /**
     * Checks if any editing is allowed.
     *
     * @return true if current mode is not NONE
     */
    public boolean isEditingAllowed() {
        return currentMode != EditMode.NONE;
    }
}
