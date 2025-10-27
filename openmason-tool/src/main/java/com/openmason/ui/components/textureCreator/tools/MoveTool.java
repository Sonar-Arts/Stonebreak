package com.openmason.ui.components.textureCreator.tools;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.*;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.moveTool.commands.TransformCommandFactory;
import com.openmason.ui.components.textureCreator.tools.moveTool.handles.HandleManager;
import com.openmason.ui.components.textureCreator.tools.moveTool.rendering.TransformPreviewRenderer;
import com.openmason.ui.components.textureCreator.tools.moveTool.state.DragState;
import com.openmason.ui.components.textureCreator.tools.moveTool.state.TransformState;
import com.openmason.ui.components.textureCreator.tools.moveTool.transform.TransformCalculator;
import com.openmason.ui.components.textureCreator.transform.TransformHandle;
import imgui.ImDrawList;

/**
 * Move tool controller with Photoshop-style transform controls.
 * This controller coordinates specialized modules:
 * - HandleManager: Handle generation and lookup
 * - TransformCalculator: Transform mathematics
 * - TransformPreviewRenderer: Rendering logic
 * - TransformCommandFactory: Command creation
 *
 * Supports:
 * - Translation (drag interior/center handle)
 * - Corner scaling with opposite anchor (Shift = proportional)
 * - Edge stretching (one-axis scaling)
 * - Rotation around center (Shift = 15° increments)
 *
 */
public class MoveTool implements DrawingTool {

    // Specialized modules
    private final HandleManager handleManager = new HandleManager();
    private final TransformCalculator calculator = new TransformCalculator();
    private final TransformPreviewRenderer renderer = new TransformPreviewRenderer();
    private final TransformCommandFactory commandFactory = new TransformCommandFactory();

    // Current state
    private DragState dragState = DragState.IDLE;
    private SelectionRegion currentSelection = null;
    private TransformHandle hoveredHandle = null;
    private TransformHandle activeHandle = null;
    private final TransformState transformState = new TransformState();

    // Keyboard state
    private boolean shiftHeld = false;

    // Tool options
    private boolean uniformScaling = false; // When true, forces proportional/uniform scaling

    // Result for CanvasPanel
    private Command completedCommand = null;
    private SelectionRegion updatedSelection = null;
    private boolean transformPerformed = false;

    // ==================== PUBLIC API ====================

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (currentSelection == null || handleManager.isEmpty()) {
            return;
        }

        // Find clicked handle using distance-based detection (excluding center)
        TransformHandle clicked = handleManager.findNearestHandle(x, y, false);

        if (clicked != null) {
            // Clicked on a specific handle (corner/edge/rotation) - start the appropriate drag operation
            startDrag(clicked, x, y);
        } else if (currentSelection.contains(x, y)) {
            // Clicked anywhere inside selection (not on a transform handle) - start moving
            TransformHandle centerHandle = handleManager.findCenterHandle();
            if (centerHandle != null) {
                startDrag(centerHandle, x, y);
            }
        }
        // If clicked outside selection and not on any handle, do nothing
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (dragState == DragState.IDLE) {
            return;
        }

        // Delegate to calculator based on drag state
        boolean proportional = shiftHeld || uniformScaling;
        switch (dragState) {
            case MOVING -> calculator.updateMoving(transformState, x, y);
            case SCALING_CORNER -> calculator.updateScaling(transformState, x, y, activeHandle, proportional);
            case STRETCHING_EDGE -> calculator.updateStretching(transformState, x, y, activeHandle, proportional);
            case ROTATING -> calculator.updateRotating(transformState, x, y, currentSelection, shiftHeld);
        }
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        if (dragState == DragState.IDLE) {
            return;
        }

        // Delegate to command factory based on drag state
        TransformCommandFactory.CommandResult result = null;
        switch (dragState) {
            case MOVING -> result = commandFactory.createMoveCommand(canvas, currentSelection, transformState);
            case SCALING_CORNER, STRETCHING_EDGE -> result = commandFactory.createScaleCommand(canvas, currentSelection, transformState);
            case ROTATING -> result = commandFactory.createRotateCommand(canvas, currentSelection, transformState);
        }

        // Store result if command was created
        if (result != null && result.hasCommand()) {
            completedCommand = result.getCommand();
            updatedSelection = result.getUpdatedSelection();
            transformPerformed = true;
        }

        // Reset state
        dragState = DragState.IDLE;
        activeHandle = null;
        transformState.clearRotatedGeometry();
    }

    /**
     * Updates the current selection and regenerates handles.
     * Works with any SelectionRegion type (rectangular, free-form, etc.)
     * Handles are generated based on the selection's bounding box.
     *
     * @param selection The selection region to update
     * @param zoom Current zoom level (for screen-space handle positioning)
     */
    public void updateSelection(SelectionRegion selection, float zoom) {
        if (selection != null && !selection.isEmpty()) {
            if (!selection.equals(currentSelection)) {
                currentSelection = selection;
                handleManager.generateHandles(selection, zoom);
            }
        } else {
            currentSelection = null;
            handleManager.clear();
        }
    }

    /**
     * Updates shift key state for constrained operations.
     */
    public void setShiftHeld(boolean shiftHeld) {
        this.shiftHeld = shiftHeld;
    }

    /**
     * Sets whether uniform scaling should be enabled for transform operations.
     * When true, forces proportional/uniform scaling on both axes (same as holding Shift key).
     * Applies to both corner scaling and edge stretching.
     *
     * @param uniform true to enable uniform scaling, false to allow independent axis scaling
     */
    public void setUniformScaling(boolean uniform) {
        this.uniformScaling = uniform;
    }

    /**
     * Checks if uniform scaling is enabled for transform operations.
     *
     * @return true if uniform scaling is enabled, false otherwise
     */
    public boolean isUniformScaling() {
        return uniformScaling;
    }

    /**
     * Updates hovered handle for cursor feedback.
     */
    public void updateHoveredHandle(int x, int y) {
        // Find any handle (including center) for cursor feedback
        hoveredHandle = handleManager.findNearestHandle(x, y, true);
    }

    /**
     * Gets cursor type for the current hovered handle.
     */
    public TransformHandle.CursorType getCursorType() {
        return hoveredHandle != null ? hoveredHandle.getCursorType() : TransformHandle.CursorType.MOVE;
    }

    /**
     * Renders transform handles and preview.
     */
    public void renderTransformHandles(ImDrawList drawList, float canvasX, float canvasY, float zoom) {
        renderer.render(drawList, handleManager.getHandles(), hoveredHandle, dragState, transformState,
            canvasX, canvasY, zoom);
    }

    // ==================== DRAG STATE MANAGEMENT ====================

    private void startDrag(TransformHandle handle, int x, int y) {
        activeHandle = handle;

        // Store original selection bounds (works for any selection type)
        java.awt.Rectangle bounds = currentSelection.getBounds();
        int x1 = bounds.x;
        int y1 = bounds.y;
        int x2 = bounds.x + bounds.width - 1;
        int y2 = bounds.y + bounds.height - 1;

        // Initialize transform state
        transformState.initialize(x1, y1, x2, y2, x, y);

        // Determine drag state from handle type
        if (handle.isCenter()) {
            dragState = DragState.MOVING;
        } else if (handle.isRotation()) {
            dragState = DragState.ROTATING;
        } else if (handle.isCorner()) {
            dragState = DragState.SCALING_CORNER;
        } else if (handle.isEdge()) {
            dragState = DragState.STRETCHING_EDGE;
        }
    }


    // ==================== RESULT API FOR CANVASPANEL ====================

    public boolean wasTransformPerformed() {
        return transformPerformed;
    }

    public Command getCompletedCommand() {
        return completedCommand;
    }

    public SelectionRegion getUpdatedSelection() {
        return updatedSelection;
    }

    public void clearTransformPerformedFlag() {
        transformPerformed = false;
        completedCommand = null;
        updatedSelection = null;
    }

    // ==================== DRAWINGTOOL INTERFACE ====================

    @Override
    public void reset() {
        dragState = DragState.IDLE;
        currentSelection = null;
        handleManager.clear();
        hoveredHandle = null;
        activeHandle = null;
        completedCommand = null;
        updatedSelection = null;
        transformPerformed = false;
        shiftHeld = false;
        uniformScaling = false;
        transformState.clearRotatedGeometry();
    }

    @Override
    public String getName() {
        return "Move";
    }

    @Override
    public String getDescription() {
        return "Move and transform selected region";
    }
}
