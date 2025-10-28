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
    private final PixelTransformer pixelTransformer;

    // Transform state
    private SelectionRegion originalSelection;
    private TransformState currentTransform;
    private Map<Point, Integer> extractedPixels;
    private Map<Point, Integer> transformedPixels;

    // Drag tracking
    private Point dragStart;
    private Point originalDragStart; // Store original start point for scaling calculations
    private HandleType draggedHandle;
    private boolean pixelsExtracted;

    // Canvas reference for restoration
    private PixelCanvas canvasWithExtractedPixels;

    // Visual feedback
    private HandleType hoveredHandle;

    // Command for undo/redo (created on commit)
    private MoveSelectionCommand pendingCommand;

    public MoveToolController() {
        this.selectionManager = null; // Will be set externally
        this.handleDetector = new HandleDetector();
        this.transformCalculator = new TransformCalculator();
        this.renderer = new SelectionTransformRenderer();
        this.pixelTransformer = new PixelTransformer();
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

        // Check if clicking inside selection bounds (transformed)
        Rectangle bounds = selection.getBounds();

        // Apply transform to check if click is inside transformed selection
        int transformedX = x - currentTransform.getTranslateX();
        int transformedY = y - currentTransform.getTranslateY();
        boolean insideSelection = selection.contains(transformedX, transformedY);

        if (currentState == State.IDLE) {
            if (insideSelection) {
                // First click on selection - enter clicked state (show handles)
                currentState = State.CLICKED_SELECTION;
                originalSelection = selection;
                currentTransform = TransformState.identity();
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
                if (pixelsExtracted) {
                    commitTransform(canvas);
                }
                currentState = State.IDLE;
            }
        } else {
            // In other states, clicking outside commits
            if (!insideSelection && pixelsExtracted) {
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
            // Commit the transform if pixels were moved
            if (pixelsExtracted && !currentTransform.isIdentity()) {
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

        // Extract pixels on first drag
        if (!pixelsExtracted) {
            extractPixels(selection, canvas);
        }

        originalSelection = selection;
    }

    private void startSelectionDrag(int x, int y, SelectionRegion selection, PixelCanvas canvas) {
        currentState = State.DRAGGING_SELECTION;
        dragStart = new Point(x, y);

        // Extract pixels on first drag
        if (!pixelsExtracted) {
            extractPixels(selection, canvas);
        }

        originalSelection = selection;
    }

    private void continueHandleDrag(int x, int y, PixelCanvas canvas) {
        if (dragStart == null || draggedHandle == null) {
            return;
        }

        Point currentPoint = new Point(x, y);

        // For rotation, use incremental calculation (frame-by-frame delta)
        // For scaling, use cumulative calculation (total delta from original start)
        Point startPoint = draggedHandle.isRotation() ? dragStart : originalDragStart;

        // Calculate new transform
        // Corner handles maintain aspect ratio by default (like Photoshop)
        boolean maintainAspectRatio = draggedHandle.isCorner();
        TransformState newTransform = transformCalculator.calculateTransform(
                draggedHandle,
                startPoint,
                currentPoint,
                originalSelection,
                currentTransform,
                maintainAspectRatio
        );

        // For rotation, accumulate the change; for scaling, replace the transform
        if (draggedHandle.isRotation()) {
            currentTransform = newTransform;
            // Update drag start for next rotation frame (incremental)
            dragStart = currentPoint;
        } else {
            // For scaling, use the calculated transform directly (cumulative from original start)
            currentTransform = newTransform;
        }

        // Apply transform and update canvas
        applyTransformToCanvas(canvas);
    }

    private void continueSelectionDrag(int x, int y, PixelCanvas canvas) {
        if (dragStart == null) {
            return;
        }

        Point currentPoint = new Point(x, y);

        // Calculate translation
        int dx = currentPoint.x - dragStart.x;
        int dy = currentPoint.y - dragStart.y;

        // Update transform
        currentTransform = currentTransform.toBuilder()
                .translate(
                        currentTransform.getTranslateX() + dx,
                        currentTransform.getTranslateY() + dy
                )
                .build();

        // Apply transform and update canvas
        applyTransformToCanvas(canvas);

        // Update drag start for next frame
        dragStart = currentPoint;
    }

    private void extractPixels(SelectionRegion selection, PixelCanvas canvas) {
        // Extract pixels from selection
        extractedPixels = pixelTransformer.extractSelectionPixels(canvas, selection);

        // Clear original area (cut-style)
        // Bypass selection constraint to allow clearing
        canvas.setBypassSelectionConstraint(true);
        try {
            pixelTransformer.clearSelectionArea(canvas, selection);
        } finally {
            canvas.setBypassSelectionConstraint(false);
        }

        pixelsExtracted = true;
        canvasWithExtractedPixels = canvas; // Store reference for potential restoration
    }

    private void applyTransformToCanvas(PixelCanvas canvas) {
        if (!pixelsExtracted || extractedPixels == null) {
            return;
        }

        // Bypass selection constraint to allow pasting outside original selection bounds
        canvas.setBypassSelectionConstraint(true);
        try {
            // Clear canvas in original area (already done) and transformed area
            if (transformedPixels != null) {
                // Clear previous transformed pixels
                for (Point point : transformedPixels.keySet()) {
                    if (canvas.isValidCoordinate(point.x, point.y)) {
                        canvas.setPixel(point.x, point.y, 0x00000000);
                    }
                }
            }

            // Apply transform to pixels
            Rectangle originalBounds = originalSelection.getBounds();
            transformedPixels = pixelTransformer.applyTransform(
                    extractedPixels, currentTransform, originalBounds);

            // Paste transformed pixels
            pixelTransformer.pastePixels(canvas, transformedPixels);
        } finally {
            canvas.setBypassSelectionConstraint(false);
        }

        // Note: canvas modification version is automatically incremented by setPixel() calls
    }

    private void commitTransform(PixelCanvas canvas) {
        if (!pixelsExtracted || extractedPixels == null) {
            System.out.println("[MoveToolController] commitTransform: no pixels extracted");
            return;
        }

        System.out.println("[MoveToolController] Committing transform: " + currentTransform);
        System.out.println("[MoveToolController] Original selection bounds: " + originalSelection.getBounds());

        // Create absolute coordinate map for original pixels
        Map<Point, Integer> absoluteOriginalPixels = createAbsolutePixelMap(
                extractedPixels, originalSelection.getBounds());

        // Create transformed selection
        SelectionRegion transformedSelection = pixelTransformer.transformSelection(
                originalSelection, currentTransform);

        System.out.println("[MoveToolController] Transformed selection bounds: " +
                (transformedSelection != null ? transformedSelection.getBounds() : "null"));

        // Create command (with SelectionManager if available)
        pendingCommand = new MoveSelectionCommand(
                canvas,
                selectionManager,
                originalSelection,
                transformedSelection,
                currentTransform,
                absoluteOriginalPixels,
                transformedPixels
        );

        System.out.println("[MoveToolController] Created pending command: " + pendingCommand.getDescription());

        // Execute command (already applied to canvas during dragging, but this updates undo stack)
        // Note: Command will be added to history by external controller

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

        // Clear canvas reference before reset so pixels aren't restored
        canvasWithExtractedPixels = null;

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

        // Render selection with handles
        renderer.render(drawList, selection, canvasState, currentTransform, showHandles, hoveredHandle,
                canvasDisplayX, canvasDisplayY);

        // Render preview during drag
        if (currentState == State.DRAGGING_HANDLE || currentState == State.DRAGGING_SELECTION) {
            renderer.renderPreview(drawList, selection, canvasState, currentTransform,
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

        hoveredHandle = handleDetector.getHandleAt(mouseX, mouseY, selection, canvasState, currentTransform,
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
     * Automatically restores pixels if they were extracted but not committed.
     */
    @Override
    public void reset() {
        // Auto-restore pixels if they were extracted but not committed
        if (pixelsExtracted && canvasWithExtractedPixels != null &&
            extractedPixels != null && originalSelection != null) {

            System.out.println("[MoveToolController] Auto-restoring pixels on reset");

            // Bypass selection constraint for restoration
            canvasWithExtractedPixels.setBypassSelectionConstraint(true);
            try {
                // Clear any transformed pixels
                if (transformedPixels != null) {
                    for (Point point : transformedPixels.keySet()) {
                        if (canvasWithExtractedPixels.isValidCoordinate(point.x, point.y)) {
                            canvasWithExtractedPixels.setPixel(point.x, point.y, 0x00000000);
                        }
                    }
                }

                // Restore original pixels
                Rectangle originalBounds = originalSelection.getBounds();
                for (Map.Entry<Point, Integer> entry : extractedPixels.entrySet()) {
                    Point relativePoint = entry.getKey();
                    int absoluteX = originalBounds.x + relativePoint.x;
                    int absoluteY = originalBounds.y + relativePoint.y;

                    if (canvasWithExtractedPixels.isValidCoordinate(absoluteX, absoluteY)) {
                        canvasWithExtractedPixels.setPixel(absoluteX, absoluteY, entry.getValue());
                    }
                }
            } finally {
                canvasWithExtractedPixels.setBypassSelectionConstraint(false);
            }
        }

        currentState = State.IDLE;
        originalSelection = null;
        currentTransform = TransformState.identity();
        extractedPixels = null;
        transformedPixels = null;
        dragStart = null;
        originalDragStart = null;
        draggedHandle = null;
        pixelsExtracted = false;
        hoveredHandle = null;
        canvasWithExtractedPixels = null;
        // Note: pendingCommand is NOT cleared here - it must survive until executed by CanvasPanel
        // Use clearPendingCommand() to explicitly clear it after execution
    }

    /**
     * Cancels the current transformation and restores original state.
     * Called when user presses ESC or switches tools without committing.
     */
    public void cancelAndReset(PixelCanvas canvas) {
        if (pixelsExtracted && extractedPixels != null && originalSelection != null) {
            // Bypass selection constraint for restoration
            canvas.setBypassSelectionConstraint(true);
            try {
                // Clear any transformed pixels
                if (transformedPixels != null) {
                    for (Point point : transformedPixels.keySet()) {
                        if (canvas.isValidCoordinate(point.x, point.y)) {
                            canvas.setPixel(point.x, point.y, 0x00000000); // Clear to transparent
                        }
                    }
                }

                // Restore original pixels at original location
                Rectangle originalBounds = originalSelection.getBounds();
                for (Map.Entry<Point, Integer> entry : extractedPixels.entrySet()) {
                    Point relativePoint = entry.getKey();
                    int absoluteX = originalBounds.x + relativePoint.x;
                    int absoluteY = originalBounds.y + relativePoint.y;

                    if (canvas.isValidCoordinate(absoluteX, absoluteY)) {
                        canvas.setPixel(absoluteX, absoluteY, entry.getValue());
                    }
                }
            } finally {
                canvas.setBypassSelectionConstraint(false);
            }
        }

        reset();
        clearPendingCommand();
    }

    @Override
    public String getName() {
        return "Move Tool";
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
}
