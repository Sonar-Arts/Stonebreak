package com.openmason.ui.components.textureCreator.tools.move;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.DrawCommand;
import com.openmason.ui.components.textureCreator.commands.move.MoveSelectionCommand;
import com.openmason.ui.components.textureCreator.selection.SelectionManager;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.DrawingTool;
import com.openmason.ui.components.textureCreator.tools.move.modules.*;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Map;

/**
 * Main controller for the move tool.
 * Implements a state machine for handling selection transformation.
 * Now integrates with SelectionManager for centralized selection state management.
 */
public class MoveToolController implements DrawingTool {

    // Tool state
    private enum State {
        IDLE,               // No interaction
        CLICKED_SELECTION,  // Selection clicked, showing handles
        DRAGGING_HANDLE,    // Actively dragging a handle
        DRAGGING_SELECTION  // Actively dragging selection body
    }

    private State currentState = State.IDLE;

    // Selection manager (optional - can be null for standalone usage)
    private SelectionManager selectionManager;

    // Modules
    private final HandleDetector handleDetector;
    private final TransformCalculator transformCalculator;
    private final SelectionTransformRenderer renderer;

    // Non-destructive transform layer (replaces extractedPixels/transformedPixels/pixelsExtracted)
    private TransformLayer activeLayer;

    // Drag tracking
    private Point dragStart;
    private Point originalDragStart; // Store original start point for scaling calculations
    private HandleType draggedHandle;

    // Visual feedback
    private HandleType hoveredHandle;

    // Modifier keys
    private boolean shiftKeyHeld = false;

    // Command for undo/redo (created on commit)
    private MoveSelectionCommand pendingCommand;

    public MoveToolController() {
        this.selectionManager = null; // Will be set externally
        this.handleDetector = new HandleDetector();
        this.transformCalculator = new TransformCalculator();
        this.renderer = new SelectionTransformRenderer();
        reset();
    }

    /**
     * Sets the SelectionManager for this move tool.
     * Should be called after construction to enable selection state management.
     * @param selectionManager The SelectionManager instance
     */
    public void setSelectionManager(SelectionManager selectionManager) {
        this.selectionManager = selectionManager;
    }

    /**
     * Updates the modifier key states.
     * Should be called before mouse events to ensure correct behavior.
     * @param shiftHeld Whether the Shift key is currently held
     */
    public void setModifierKeys(boolean shiftHeld) {
        this.shiftKeyHeld = shiftHeld;
    }

    @Override
    public void onMouseDown(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        // Note: x, y are already in canvas coordinates
        // Handle detection is done externally in updateHoveredHandle via screen coordinates

        // Get current selection from canvas
        SelectionRegion selection = canvas.getActiveSelection();

        if (selection == null || selection.isEmpty()) {
            currentState = State.IDLE;
            return;
        }

        // Check if clicking inside selection bounds
        Rectangle bounds = selection.getBounds();
        boolean insideSelection = selection.contains(x, y);

        if (currentState == State.IDLE) {
            if (insideSelection) {
                // First click on selection - enter clicked state (show handles)
                currentState = State.CLICKED_SELECTION;
            }
        } else if (currentState == State.CLICKED_SELECTION) {
            // Check if a handle was clicked (hoveredHandle is set by updateHoveredHandle)
            if (hoveredHandle != null) {
                // Start dragging the handle
                startHandleDrag(hoveredHandle, x, y, selection, canvas);
            } else if (insideSelection) {
                // Start dragging selection body
                startSelectionDrag(x, y, selection, canvas);
            } else {
                // Clicked outside selection - commit if we have changes
                if (activeLayer != null && activeLayer.hasChanges()) {
                    commitTransform(canvas);
                }
                currentState = State.IDLE;
            }
        } else {
            // In other states, clicking outside commits
            if (!insideSelection && activeLayer != null && activeLayer.hasChanges()) {
                commitTransform(canvas);
                currentState = State.IDLE;
            }
        }
    }

    @Override
    public void onMouseDrag(int x, int y, int color, PixelCanvas canvas, DrawCommand command) {
        if (currentState == State.DRAGGING_HANDLE) {
            continueHandleDrag(x, y, canvas);
        } else if (currentState == State.DRAGGING_SELECTION) {
            continueSelectionDrag(x, y, canvas);
        }
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        if (currentState == State.DRAGGING_HANDLE || currentState == State.DRAGGING_SELECTION) {
            // Commit the transform if layer has changes
            if (activeLayer != null && activeLayer.hasChanges()) {
                commitTransform(canvas);
                currentState = State.IDLE;
            } else {
                // Return to clicked state after dragging (no actual transform)
                currentState = State.CLICKED_SELECTION;
            }
            dragStart = null;
            draggedHandle = null;
        }
    }

    private void startHandleDrag(HandleType handle, int x, int y, SelectionRegion selection, PixelCanvas canvas) {
        currentState = State.DRAGGING_HANDLE;
        draggedHandle = handle;
        dragStart = new Point(x, y);
        originalDragStart = new Point(x, y); // Store original start for cumulative calculations

        // Create non-destructive transform layer on first drag
        if (activeLayer == null) {
            activeLayer = new TransformLayer(canvas, selection);
        }
    }

    private void startSelectionDrag(int x, int y, SelectionRegion selection, PixelCanvas canvas) {
        currentState = State.DRAGGING_SELECTION;
        dragStart = new Point(x, y);

        // Create non-destructive transform layer on first drag
        if (activeLayer == null) {
            activeLayer = new TransformLayer(canvas, selection);
        }
    }

    private void continueHandleDrag(int x, int y, PixelCanvas canvas) {
        if (dragStart == null || draggedHandle == null || activeLayer == null) {
            return;
        }

        Point currentPoint = new Point(x, y);

        // Always use cumulative calculation from original drag start for all transforms
        // This ensures mathematical correctness by avoiding floating-point accumulation
        // Corner handles: Shift key locks aspect ratio (independent scaling by default)
        // Edge handles: Never maintain aspect ratio (single-axis scaling)
        boolean maintainAspectRatio = draggedHandle.isCorner() && shiftKeyHeld;
        TransformState newTransform = transformCalculator.calculateTransform(
                draggedHandle,
                originalDragStart,  // Always use original start for cumulative calculation
                currentPoint,
                activeLayer.getOriginalSelection(),
                activeLayer.getTransform(),
                maintainAspectRatio
        );

        // Update layer transform (non-destructive - canvas NOT modified)
        activeLayer.setTransform(newTransform);
    }

    private void continueSelectionDrag(int x, int y, PixelCanvas canvas) {
        if (dragStart == null || activeLayer == null) {
            return;
        }

        Point currentPoint = new Point(x, y);

        // Calculate translation
        int dx = currentPoint.x - dragStart.x;
        int dy = currentPoint.y - dragStart.y;

        // Update transform
        TransformState currentTransform = activeLayer.getTransform();
        TransformState newTransform = currentTransform.toBuilder()
                .translate(
                        currentTransform.getTranslateX() + dx,
                        currentTransform.getTranslateY() + dy
                )
                .build();

        // Update layer transform (non-destructive - canvas NOT modified)
        activeLayer.setTransform(newTransform);

        // Update drag start for next frame
        dragStart = currentPoint;
    }


    private void commitTransform(PixelCanvas canvas) {
        if (activeLayer == null) {
            System.out.println("[MoveToolController] commitTransform: no active layer");
            return;
        }

        TransformState transform = activeLayer.getTransform();
        SelectionRegion originalSelection = activeLayer.getOriginalSelection();
        Rectangle originalBounds = activeLayer.getOriginalBounds();

        System.out.println("[MoveToolController] Committing transform: " + transform);
        System.out.println("[MoveToolController] Original selection bounds: " + originalBounds);

        // Create absolute coordinate map for original pixels
        Map<Point, Integer> absoluteOriginalPixels = createAbsolutePixelMap(
                activeLayer.getOriginalPixels(), originalBounds);

        // Get transformed pixels from layer (cached for performance)
        Map<Point, Integer> transformedPixels = activeLayer.getTransformedPixels();

        // Create transformed selection
        SelectionRegion transformedSelection = activeLayer.getTransformedSelection();

        System.out.println("[MoveToolController] Transformed selection bounds: " +
                (transformedSelection != null ? transformedSelection.getBounds() : "null"));

        // Commit layer to canvas (ONLY place canvas is modified)
        activeLayer.commitToCanvas(canvas);

        // Create command for undo/redo
        pendingCommand = new MoveSelectionCommand(
                canvas,
                selectionManager,
                originalSelection,
                transformedSelection,
                transform,
                absoluteOriginalPixels,
                transformedPixels
        );

        System.out.println("[MoveToolController] Created pending command: " + pendingCommand.getDescription());

        // Update selection using SelectionManager if available, otherwise update canvas directly
        if (transformedSelection != null) {
            if (selectionManager != null) {
                selectionManager.setActiveSelection(transformedSelection);
                System.out.println("[MoveToolController] Updated selection via SelectionManager");
            } else {
                canvas.setActiveSelection(transformedSelection);
                System.out.println("[MoveToolController] Updated canvas active selection (no SelectionManager)");
            }
        }

        // Discard layer (no longer needed)
        activeLayer.discard();
        activeLayer = null;

        reset();
        System.out.println("[MoveToolController] Reset complete, pending command should still exist");
    }

    private Map<Point, Integer> createAbsolutePixelMap(Map<Point, Integer> relativePixels, Rectangle bounds) {
        java.util.HashMap<Point, Integer> absolutePixels = new java.util.HashMap<>();

        for (Map.Entry<Point, Integer> entry : relativePixels.entrySet()) {
            Point relativePoint = entry.getKey();
            Point absolutePoint = new Point(
                    bounds.x + relativePoint.x,
                    bounds.y + relativePoint.y
            );
            absolutePixels.put(absolutePoint, entry.getValue());
        }

        return absolutePixels;
    }

    public void renderOverlay(imgui.ImDrawList drawList, SelectionRegion selection,
                              com.openmason.ui.components.textureCreator.canvas.CanvasState canvasState,
                              float canvasDisplayX, float canvasDisplayY) {
        if (selection == null || selection.isEmpty()) {
            return;
        }

        // Determine if we should show handles
        boolean showHandles = (currentState == State.CLICKED_SELECTION ||
                              currentState == State.DRAGGING_HANDLE ||
                              currentState == State.DRAGGING_SELECTION);

        // Get transform from active layer (or identity if no layer)
        TransformState transform = (activeLayer != null) ? activeLayer.getTransform() : TransformState.identity();

        // Render selection with handles
        renderer.render(drawList, selection, canvasState, transform, showHandles, hoveredHandle,
                canvasDisplayX, canvasDisplayY);

        // Render preview during drag (composite layer overlay)
        if ((currentState == State.DRAGGING_HANDLE || currentState == State.DRAGGING_SELECTION) && activeLayer != null) {
            renderer.renderPreview(drawList, selection, canvasState, transform,
                    canvasDisplayX, canvasDisplayY);
        }
    }

    public void updateHoveredHandle(float mouseX, float mouseY, SelectionRegion selection,
                                    com.openmason.ui.components.textureCreator.canvas.CanvasState canvasState,
                                    float canvasDisplayX, float canvasDisplayY) {
        if (selection == null || currentState == State.IDLE) {
            hoveredHandle = null;
            return;
        }

        // Get transform from active layer (or identity if no layer)
        TransformState transform = (activeLayer != null) ? activeLayer.getTransform() : TransformState.identity();

        hoveredHandle = handleDetector.getHandleAt(mouseX, mouseY, selection, canvasState, transform,
                canvasDisplayX, canvasDisplayY);
    }

    public MoveSelectionCommand getPendingCommand() {
        return pendingCommand;
    }

    public void clearPendingCommand() {
        pendingCommand = null;
    }

    /**
     * Resets the tool state. This is called when switching tools or canceling operations.
     * Non-destructive: simply discards the layer without canvas modification.
     */
    @Override
    public void reset() {
        // Discard active layer if it exists (non-destructive - canvas never modified)
        if (activeLayer != null) {
            System.out.println("[MoveToolController] Discarding active layer on reset (non-destructive)");
            activeLayer.discard();
            activeLayer = null;
        }

        currentState = State.IDLE;
        dragStart = null;
        originalDragStart = null;
        draggedHandle = null;
        hoveredHandle = null;
        // Note: pendingCommand is NOT cleared here - it must survive until executed by CanvasPanel
        // Use clearPendingCommand() to explicitly clear it after execution
    }

    /**
     * Cancels the current transformation and restores original state.
     * Called when user presses ESC or switches tools without committing.
     * Non-destructive: canvas never modified, so just discard layer.
     */
    public void cancelAndReset(PixelCanvas canvas) {
        // Perfect non-destructive cancel: canvas never modified during preview!
        // Just discard the layer - no restoration needed
        if (activeLayer != null) {
            System.out.println("[MoveToolController] Canceling transform (non-destructive)");
            activeLayer.discard();
            activeLayer = null;
        }

        reset();
        clearPendingCommand();
    }

    @Override
    public String getName() {
        return "Move";
    }

    @Override
    public String getDescription() {
        return "Move, scale, and rotate selections";
    }

    public boolean isActive() {
        return currentState != State.IDLE;
    }

    public State getCurrentState() {
        return currentState;
    }

    /**
     * Gets the active transform layer for rendering.
     * Used by rendering pipeline to composite layer overlay.
     *
     * @return Active transform layer, or null if no active transform
     */
    public TransformLayer getActiveLayer() {
        return activeLayer;
    }

    /**
     * Checks if there is an active transform layer.
     *
     * @return true if layer exists, false otherwise
     */
    public boolean hasActiveLayer() {
        return activeLayer != null;
    }
}
