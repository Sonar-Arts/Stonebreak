package com.openmason.main.systems.viewport.state;

import com.openmason.main.systems.viewport.viewportRendering.TranslationCoordinator;
import com.openmason.main.systems.rendering.model.gmr.subrenders.vertex.VertexRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.edge.EdgeRenderer;
import com.openmason.main.systems.rendering.model.gmr.subrenders.face.FaceRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton manager for the current editing mode.
 * Controls which geometry type (vertex, edge, face) can be selected and edited.
 * Cancels active drag operations and clears selections when switching modes.
 * Follows Single Responsibility Principle - only manages edit mode state.
 */
public final class EditModeManager {

    private static final Logger logger = LoggerFactory.getLogger(EditModeManager.class);

    private static final EditModeManager INSTANCE = new EditModeManager();

    private EditMode currentMode = EditMode.NONE;

    // Optional reference to cancel active drags when switching modes
    private TranslationCoordinator translationCoordinator;

    // Selection states for clearing on mode switch
    private VertexSelectionState vertexSelectionState;
    private EdgeSelectionState edgeSelectionState;
    private FaceSelectionState faceSelectionState;

    // Renderers for visual updates on mode switch
    private VertexRenderer vertexRenderer;
    private EdgeRenderer edgeRenderer;
    private FaceRenderer faceRenderer;

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
     * Sets selection states and renderers for clearing on mode switch.
     * Should be called during viewport initialization.
     */
    public void setSelectionComponents(VertexSelectionState vertexState, EdgeSelectionState edgeState,
                                        FaceSelectionState faceState, VertexRenderer vRenderer,
                                        EdgeRenderer eRenderer, FaceRenderer fRenderer) {
        this.vertexSelectionState = vertexState;
        this.edgeSelectionState = edgeState;
        this.faceSelectionState = faceState;
        this.vertexRenderer = vRenderer;
        this.edgeRenderer = eRenderer;
        this.faceRenderer = fRenderer;
        logger.debug("Selection components set in EditModeManager");
    }

    /**
     * Clears all selections (vertex, edge, face).
     * Called when switching edit modes to start fresh.
     */
    private void clearAllSelections() {
        if (vertexSelectionState != null) {
            vertexSelectionState.clearSelection();
        }
        if (edgeSelectionState != null) {
            edgeSelectionState.clearSelection();
        }
        if (faceSelectionState != null) {
            faceSelectionState.clearSelection();
        }

        // Update visual state
        if (vertexRenderer != null) {
            vertexRenderer.clearSelection();
        }
        if (edgeRenderer != null) {
            edgeRenderer.clearSelection();
        }
        if (faceRenderer != null) {
            faceRenderer.clearSelection();
        }

        logger.debug("Cleared all selections on mode switch");
    }

    /**
     * Cycles to the next editing mode: NONE -> VERTEX -> EDGE -> FACE -> NONE
     * Cancels any active drag operation and clears all selections before switching.
     */
    public void cycleMode() {
        // Cancel any active drag before switching modes (treats it as dropping the drag)
        if (translationCoordinator != null && translationCoordinator.isDragging()) {
            translationCoordinator.cancelDrag();
            logger.debug("Cancelled active drag before mode switch");
        }

        // Clear all selections when switching modes
        clearAllSelections();

        EditMode previousMode = currentMode;
        currentMode = currentMode.next();
        logger.debug("Edit mode changed: {} -> {}", previousMode.getDisplayName(), currentMode.getDisplayName());
    }

    /**
     * Sets the editing mode directly.
     * Cancels any active drag operation and clears all selections before switching.
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

            // Clear all selections when switching modes
            clearAllSelections();

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
