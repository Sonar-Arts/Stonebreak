package com.openmason.main.systems.viewport.state;

import com.openmason.main.systems.viewport.viewportRendering.TranslationCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton manager for the current editing mode.
 * Controls which geometry type (vertex, edge, face) can be selected and edited.
 * Cancels active drag operations when switching modes for safety.
 * Follows Single Responsibility Principle - only manages edit mode state.
 */
public final class EditModeManager {

    private static final Logger logger = LoggerFactory.getLogger(EditModeManager.class);

    private static final EditModeManager INSTANCE = new EditModeManager();

    private EditMode currentMode = EditMode.NONE;

    // Optional reference to cancel active drags when switching modes
    private TranslationCoordinator translationCoordinator;

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
     * Sets the translation coordinator for cancelling active drags on mode switch.
     * Should be called during viewport initialization.
     *
     * @param coordinator The translation coordinator
     */
    public void setTranslationCoordinator(TranslationCoordinator coordinator) {
        this.translationCoordinator = coordinator;
        logger.debug("TranslationCoordinator set in EditModeManager");
    }

    /**
     * Cycles to the next editing mode: NONE -> VERTEX -> EDGE -> FACE -> NONE
     * Cancels any active drag operation before switching for safety.
     */
    public void cycleMode() {
        // Cancel any active drag before switching modes (treats it as dropping the drag)
        if (translationCoordinator != null && translationCoordinator.isDragging()) {
            translationCoordinator.cancelDrag();
            logger.debug("Cancelled active drag before mode switch");
        }

        EditMode previousMode = currentMode;
        currentMode = currentMode.next();
        logger.debug("Edit mode changed: {} -> {}", previousMode.getDisplayName(), currentMode.getDisplayName());
    }

    /**
     * Sets the editing mode directly.
     * Cancels any active drag operation before switching for safety.
     *
     * @param mode The mode to set
     */
    public void setMode(EditMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("EditMode cannot be null");
        }
        if (currentMode != mode) {
            // Cancel any active drag before switching modes
            if (translationCoordinator != null && translationCoordinator.isDragging()) {
                translationCoordinator.cancelDrag();
                logger.debug("Cancelled active drag before mode switch");
            }

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
