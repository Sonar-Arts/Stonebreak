package com.openmason.ui.components.textureCreator.tools;

import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.commands.*;
import com.openmason.ui.components.textureCreator.selection.FreeSelection;
import com.openmason.ui.components.textureCreator.selection.RectangularSelection;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.transform.TransformHandle;
import com.openmason.ui.components.textureCreator.transform.TransformHandleRenderer;
import imgui.ImDrawList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Move tool with Photoshop-style transform controls - COMPLETE REWRITE.
 * Direct coordinate manipulation (KISS principle) - no abstraction layers.
 * <p>
 * Supports:
 * - Translation (drag interior/center handle)
 * - Corner scaling with opposite anchor (Shift = proportional)
 * - Edge stretching (one-axis scaling)
 * - Rotation around center (Shift = 15° increments)
 * <p>
 * SOLID: Single responsibility - manages selection transformation
 * KISS: Direct coordinate math, no intermediate abstractions
 * DRY: Shared handle generation and rendering
 * YAGNI: No unnecessary features
 *
 * @author Open Mason Team
 */
public class MoveTool implements DrawingTool {

    // Handle configuration - hit radii in canvas pixels (1 pixel = pixel-perfect precision)
    private static final double CORNER_HANDLE_RADIUS = 1.0;      // Corner handles - pixel-perfect
    private static final double EDGE_HANDLE_RADIUS = 1.0;        // Edge handles - pixel-perfect
    private static final double ROTATION_HANDLE_RADIUS = 1.0;    // Rotation handle - pixel-perfect
    private static final double CENTER_HANDLE_RADIUS = 3.0;      // Center handle - larger for easier clicking
    private static final double ROTATION_HANDLE_SCREEN_OFFSET = 40.0; // Distance above top edge in screen pixels (constant regardless of zoom)

    // Transform state enum
    private enum DragState {
        IDLE,
        MOVING,
        SCALING_CORNER,
        STRETCHING_EDGE,
        ROTATING
    }

    // Current state
    private DragState dragState = DragState.IDLE;
    private SelectionRegion currentSelection = null;
    private List<TransformHandle> handles = new ArrayList<>();
    private TransformHandle hoveredHandle = null;
    private TransformHandle activeHandle = null;
    private final TransformHandleRenderer renderer = new TransformHandleRenderer();
    private float currentZoom = 1.0f; // Current zoom level for screen-space calculations

    // Drag tracking
    private int dragStartX, dragStartY;
    private int originalX1, originalY1, originalX2, originalY2;
    private int previewX1, previewY1, previewX2, previewY2;
    private double rotationAngleDegrees = 0.0;

    // Scale tracking (for maintaining free selection shapes)
    private int scaleAnchorX, scaleAnchorY;
    private double scaleFactorX, scaleFactorY;

    // Rotated geometry tracking (for live rotation visualization)
    private double[] rotatedCorners = null; // [x1,y1, x2,y1, x2,y2, x1,y2] - 4 corners (8 values)
    private List<TransformHandle> rotatedHandles = null; // Handles in rotated positions
    private FreeSelection rotatedFreeSelectionPreview = null; // Preview of rotated free selection

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
        if (currentSelection == null || handles.isEmpty()) {
            return;
        }

        // Find clicked handle using distance-based detection (excluding center)
        TransformHandle clicked = findNearestHandle(x, y, false);

        if (clicked != null) {
            // Clicked on a specific handle (corner/edge/rotation) - start the appropriate drag operation
            startDrag(clicked, x, y);
        } else if (currentSelection.contains(x, y)) {
            // Clicked anywhere inside selection (not on a transform handle) - start moving
            TransformHandle centerHandle = findCenterHandle();
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

        switch (dragState) {
            case MOVING -> updateMoving(x, y);
            case SCALING_CORNER -> updateScaling(x, y);
            case STRETCHING_EDGE -> updateStretching(x, y);
            case ROTATING -> updateRotating(x, y);
        }
    }

    @Override
    public void onMouseUp(int color, PixelCanvas canvas, DrawCommand command) {
        if (dragState == DragState.IDLE) {
            return;
        }

        // Create appropriate command based on what was dragged
        switch (dragState) {
            case MOVING -> createMoveCommand(canvas);
            case SCALING_CORNER, STRETCHING_EDGE -> createScaleCommand(canvas);
            case ROTATING -> createRotateCommand(canvas);
        }

        dragState = DragState.IDLE;
        activeHandle = null;

        // Clear rotated geometry
        rotatedCorners = null;
        rotatedHandles = null;
        rotatedFreeSelectionPreview = null;
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
        currentZoom = zoom;

        if (selection != null && !selection.isEmpty()) {
            if (!selection.equals(currentSelection)) {
                currentSelection = selection;
                generateHandles();
            }
        } else {
            currentSelection = null;
            handles.clear();
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
        hoveredHandle = findNearestHandle(x, y, true);
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
        if (handles.isEmpty()) {
            return;
        }

        // Special rendering during rotation
        if (dragState == DragState.ROTATING && rotatedCorners != null && rotatedHandles != null) {
            // Render rotated selection outline
            renderer.renderRotatedSelection(drawList, rotatedCorners, canvasX, canvasY, zoom);

            // Render rotated free selection preview if available
            if (rotatedFreeSelectionPreview != null) {
                renderRotatedFreeSelectionPreview(drawList, canvasX, canvasY, zoom);
            }

            // Render rotated handles
            renderer.render(drawList, rotatedHandles, hoveredHandle, canvasX, canvasY, zoom);

            // Render angle indicator (using visual box center)
            double boxRight = originalX2 + 1;
            double boxBottom = originalY2 + 1;
            double centerX = (originalX1 + boxRight) / 2.0;
            double centerY = (originalY1 + boxBottom) / 2.0;
            renderer.renderAngleIndicator(drawList, rotationAngleDegrees, centerX, centerY, canvasX, canvasY, zoom);
        } else {
            // Normal rendering
            renderer.render(drawList, handles, hoveredHandle, canvasX, canvasY, zoom);

            // Render preview bounds during non-rotation drag
            if (dragState != DragState.IDLE) {
                renderPreviewBounds(drawList, canvasX, canvasY, zoom);
            }
        }
    }

    // ==================== DRAG STATE MANAGEMENT ====================

    private void startDrag(TransformHandle handle, int x, int y) {
        activeHandle = handle;
        dragStartX = x;
        dragStartY = y;

        // Store original selection bounds (works for any selection type)
        java.awt.Rectangle bounds = currentSelection.getBounds();
        originalX1 = bounds.x;
        originalY1 = bounds.y;
        originalX2 = bounds.x + bounds.width - 1;
        originalY2 = bounds.y + bounds.height - 1;

        // Initialize preview to original
        previewX1 = originalX1;
        previewY1 = originalY1;
        previewX2 = originalX2;
        previewY2 = originalY2;
        rotationAngleDegrees = 0.0;

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

    // ==================== TRANSFORM MATH (Direct Coordinate Manipulation) ====================

    private void updateMoving(int currentX, int currentY) {
        int deltaX = currentX - dragStartX;
        int deltaY = currentY - dragStartY;

        previewX1 = originalX1 + deltaX;
        previewY1 = originalY1 + deltaY;
        previewX2 = originalX2 + deltaX;
        previewY2 = originalY2 + deltaY;
    }

    private void updateScaling(int currentX, int currentY) {
        // Get anchor point (opposite corner)
        int[] anchor = getAnchorForCorner(activeHandle.getType());
        int anchorX = anchor[0];
        int anchorY = anchor[1];

        // Store anchor for command creation
        scaleAnchorX = anchorX;
        scaleAnchorY = anchorY;

        // Get original dragged corner position
        int[] origCorner = getCornerPosition(activeHandle.getType(), originalX1, originalY1, originalX2, originalY2);
        int origCornerX = origCorner[0];
        int origCornerY = origCorner[1];

        // Calculate scale factors (preserve sign for flipping)
        double originalWidth = origCornerX - anchorX;
        double originalHeight = origCornerY - anchorY;
        double newWidth = currentX - anchorX;
        double newHeight = currentY - anchorY;

        // Prevent division by zero
        if (Math.abs(originalWidth) < 1) originalWidth = originalWidth < 0 ? -1 : 1;
        if (Math.abs(originalHeight) < 1) originalHeight = originalHeight < 0 ? -1 : 1;

        double scaleX = newWidth / originalWidth;
        double scaleY = newHeight / originalHeight;

        // Proportional scaling with Shift or uniform scaling option (use absolute values for comparison)
        if (shiftHeld || uniformScaling) {
            double uniformScale = Math.max(Math.abs(scaleX), Math.abs(scaleY));
            // Preserve signs
            scaleX = uniformScale * Math.signum(scaleX);
            scaleY = uniformScale * Math.signum(scaleY);
        }

        // Store scale factors for command creation
        scaleFactorX = scaleX;
        scaleFactorY = scaleY;

        // Calculate new bounds
        int newX1 = anchorX + (int) Math.round((originalX1 - anchorX) * scaleX);
        int newY1 = anchorY + (int) Math.round((originalY1 - anchorY) * scaleY);
        int newX2 = anchorX + (int) Math.round((originalX2 - anchorX) * scaleX);
        int newY2 = anchorY + (int) Math.round((originalY2 - anchorY) * scaleY);

        // Normalize (ensure x1 < x2, y1 < y2)
        previewX1 = Math.min(newX1, newX2);
        previewY1 = Math.min(newY1, newY2);
        previewX2 = Math.max(newX1, newX2);
        previewY2 = Math.max(newY1, newY2);
    }

    private void updateStretching(int currentX, int currentY) {
        // Start with original bounds
        previewX1 = originalX1;
        previewY1 = originalY1;
        previewX2 = originalX2;
        previewY2 = originalY2;

        // Original dimensions
        int originalWidth = originalX2 - originalX1;
        int originalHeight = originalY2 - originalY1;

        // Calculate scale factor for the dragged edge
        double primaryScaleFactor = 1.0;
        boolean isVerticalEdge = false;

        // Determine primary scale factor and anchor based on dragged edge
        switch (activeHandle.getType()) {
            case EDGE_TOP -> {
                int deltaY = currentY - originalY1;
                int newHeight = originalHeight - deltaY;
                primaryScaleFactor = (double) newHeight / originalHeight;
                isVerticalEdge = true;
                scaleAnchorX = originalX1;
                scaleAnchorY = originalY2;
            }
            case EDGE_BOTTOM -> {
                int deltaY = currentY - originalY2;
                int newHeight = originalHeight + deltaY;
                primaryScaleFactor = (double) newHeight / originalHeight;
                isVerticalEdge = true;
                scaleAnchorX = originalX1;
                scaleAnchorY = originalY1;
            }
            case EDGE_LEFT -> {
                int deltaX = currentX - originalX1;
                int newWidth = originalWidth - deltaX;
                primaryScaleFactor = (double) newWidth / originalWidth;
                isVerticalEdge = false;
                scaleAnchorX = originalX2;
                scaleAnchorY = originalY1;
            }
            case EDGE_RIGHT -> {
                int deltaX = currentX - originalX2;
                int newWidth = originalWidth + deltaX;
                primaryScaleFactor = (double) newWidth / originalWidth;
                isVerticalEdge = false;
                scaleAnchorX = originalX1;
                scaleAnchorY = originalY1;
            }
        }

        // Apply uniform scaling if enabled (or Shift held)
        if (shiftHeld || uniformScaling) {
            // Both axes scale by the same factor
            scaleFactorX = primaryScaleFactor;
            scaleFactorY = primaryScaleFactor;

            // Calculate new bounds with uniform scaling from anchor
            int newX1 = scaleAnchorX + (int) Math.round((originalX1 - scaleAnchorX) * scaleFactorX);
            int newY1 = scaleAnchorY + (int) Math.round((originalY1 - scaleAnchorY) * scaleFactorY);
            int newX2 = scaleAnchorX + (int) Math.round((originalX2 - scaleAnchorX) * scaleFactorX);
            int newY2 = scaleAnchorY + (int) Math.round((originalY2 - scaleAnchorY) * scaleFactorY);

            previewX1 = newX1;
            previewY1 = newY1;
            previewX2 = newX2;
            previewY2 = newY2;
        } else {
            // Non-uniform scaling - only move the dragged edge, keep all others at original positions
            scaleFactorX = 1.0;
            scaleFactorY = 1.0;

            // Update only the dragged edge based on which edge is being stretched
            switch (activeHandle.getType()) {
                case EDGE_TOP -> {
                    scaleFactorY = primaryScaleFactor;
                    previewY1 = scaleAnchorY + (int) Math.round((originalY1 - scaleAnchorY) * scaleFactorY);
                    previewY2 = originalY2; // Anchor edge stays fixed
                    // X coordinates already set to original values at start of method
                }
                case EDGE_BOTTOM -> {
                    scaleFactorY = primaryScaleFactor;
                    previewY1 = originalY1; // Anchor edge stays fixed
                    previewY2 = scaleAnchorY + (int) Math.round((originalY2 - scaleAnchorY) * scaleFactorY);
                    // X coordinates already set to original values at start of method
                }
                case EDGE_LEFT -> {
                    scaleFactorX = primaryScaleFactor;
                    previewX1 = scaleAnchorX + (int) Math.round((originalX1 - scaleAnchorX) * scaleFactorX);
                    previewX2 = originalX2; // Anchor edge stays fixed
                    // Y coordinates already set to original values at start of method
                }
                case EDGE_RIGHT -> {
                    scaleFactorX = primaryScaleFactor;
                    previewX1 = originalX1; // Anchor edge stays fixed
                    previewX2 = scaleAnchorX + (int) Math.round((originalX2 - scaleAnchorX) * scaleFactorX);
                    // Y coordinates already set to original values at start of method
                }
            }
        }

        // Normalize
        if (previewX1 > previewX2) {
            int temp = previewX1;
            previewX1 = previewX2;
            previewX2 = temp;
        }
        if (previewY1 > previewY2) {
            int temp = previewY1;
            previewY1 = previewY2;
            previewY2 = temp;
        }
    }

    private void updateRotating(int currentX, int currentY) {
        // Calculate center using visual box coordinates
        double boxRight = originalX2 + 1;
        double boxBottom = originalY2 + 1;
        double centerX = (originalX1 + boxRight) / 2.0;
        double centerY = (originalY1 + boxBottom) / 2.0;

        double angleStart = Math.toDegrees(Math.atan2(dragStartY - centerY, dragStartX - centerX));
        double angleCurrent = Math.toDegrees(Math.atan2(currentY - centerY, currentX - centerX));

        rotationAngleDegrees = angleCurrent - angleStart;

        // Snap to 15° increments with Shift
        if (shiftHeld) {
            rotationAngleDegrees = Math.round(rotationAngleDegrees / 15.0) * 15.0;
        }

        // Snap to cardinal angles (0, 90, 180, 270) within 5° threshold
        double normalizedAngle = rotationAngleDegrees % 360;
        if (normalizedAngle < 0) normalizedAngle += 360;

        double[] cardinalAngles = {0, 90, 180, 270, 360};
        double snapThreshold = 5.0;
        for (double cardinalAngle : cardinalAngles) {
            if (Math.abs(normalizedAngle - cardinalAngle) < snapThreshold) {
                rotationAngleDegrees = cardinalAngle;
                if (cardinalAngle == 360) rotationAngleDegrees = 0; // Normalize 360 to 0
                break;
            }
        }

        // Calculate rotated geometry for live visualization
        calculateRotatedCorners();
        generateRotatedHandles();

        // Generate rotated free selection preview if applicable
        if (currentSelection instanceof FreeSelection) {
            FreeSelection freeSelection = (FreeSelection) currentSelection;
            rotatedFreeSelectionPreview = freeSelection.rotate(rotationAngleDegrees);
        } else {
            rotatedFreeSelectionPreview = null;
        }
    }

    // ==================== COMMAND CREATION ====================

    private void createMoveCommand(PixelCanvas canvas) {
        int deltaX = previewX1 - originalX1;
        int deltaY = previewY1 - originalY1;

        if (deltaX != 0 || deltaY != 0) {
            // TranslateSelectionCommand works with any SelectionRegion type
            completedCommand = new TranslateSelectionCommand(canvas, currentSelection, deltaX, deltaY);
            updatedSelection = currentSelection.translate(deltaX, deltaY);
            transformPerformed = true;
        }
    }

    private void createScaleCommand(PixelCanvas canvas) {
        // Check if we're scaling a free selection - maintain its shape
        if (currentSelection instanceof FreeSelection) {
            FreeSelection freeSelection = (FreeSelection) currentSelection;

            // Scale the free selection to maintain its shape
            FreeSelection scaledFreeSelection =
                freeSelection.scale(scaleAnchorX, scaleAnchorY, scaleFactorX, scaleFactorY);

            // For non-uniform edge stretching, trim the scaled selection to exact target bounds
            // This removes fill artifacts at anchor edges while maintaining free-form shape
            boolean isNonUniformStretch = (Math.abs(scaleFactorX - 1.0) < 0.001 && Math.abs(scaleFactorY - 1.0) >= 0.001) ||
                                         (Math.abs(scaleFactorY - 1.0) < 0.001 && Math.abs(scaleFactorX - 1.0) >= 0.001);

            if (isNonUniformStretch) {
                // Trim pixels outside target bounds to prevent anchor edge artifacts
                scaledFreeSelection = trimFreeSelectionToBounds(scaledFreeSelection, previewX1, previewY1, previewX2, previewY2);
            }

            if (!scaledFreeSelection.equals(currentSelection)) {
                // Use rectangular bounds for the command, but maintain the free selection
                RectangularSelection targetBounds = new RectangularSelection(previewX1, previewY1, previewX2, previewY2);
                completedCommand = new ScaleSelectionCommand(canvas, currentSelection, targetBounds);
                updatedSelection = scaledFreeSelection; // Update to scaled free selection, not rectangle
                transformPerformed = true;
            }
        } else {
            // Rectangular selection - normal behavior
            RectangularSelection scaledSelection = new RectangularSelection(previewX1, previewY1, previewX2, previewY2);

            if (!scaledSelection.equals(currentSelection)) {
                completedCommand = new ScaleSelectionCommand(canvas, currentSelection, scaledSelection);
                updatedSelection = scaledSelection;
                transformPerformed = true;
            }
        }
    }

    /**
     * Trims a free selection to only include pixels within the specified bounds.
     * Used to remove fill artifacts at anchor edges during non-uniform scaling.
     *
     * @param selection The free selection to trim
     * @param x1 Left bound (inclusive)
     * @param y1 Top bound (inclusive)
     * @param x2 Right bound (inclusive)
     * @param y2 Bottom bound (inclusive)
     * @return A new FreeSelection containing only pixels within bounds
     */
    private FreeSelection trimFreeSelectionToBounds(FreeSelection selection, int x1, int y1, int x2, int y2) {
        Set<FreeSelection.Pixel> trimmedPixels = new HashSet<>();

        for (FreeSelection.Pixel pixel : selection.getPixels()) {
            if (pixel.x >= x1 && pixel.x <= x2 && pixel.y >= y1 && pixel.y <= y2) {
                trimmedPixels.add(pixel);
            }
        }

        // If no pixels remain after trimming, return original selection
        return trimmedPixels.isEmpty() ? selection : new FreeSelection(trimmedPixels);
    }

    private void createRotateCommand(PixelCanvas canvas) {
        if (Math.abs(rotationAngleDegrees) > 0.1) {
            // RotateSelectionCommand works with any SelectionRegion type
            completedCommand = new RotateSelectionCommand(canvas, currentSelection, rotationAngleDegrees);

            // For free selections, update to rotated coordinates
            if (currentSelection instanceof FreeSelection) {
                FreeSelection freeSelection = (FreeSelection) currentSelection;
                updatedSelection = freeSelection.rotate(rotationAngleDegrees);
            } else {
                // Rectangular selection bounds don't change for rotation
                updatedSelection = currentSelection;
            }

            transformPerformed = true;
        }
    }

    // ==================== HANDLE MANAGEMENT ====================

    private void generateHandles() {
        handles.clear();

        if (currentSelection == null) {
            return;
        }

        // Use bounds for any selection type (rectangular, free-form, etc.)
        java.awt.Rectangle bounds = currentSelection.getBounds();
        int x1 = bounds.x;
        int y1 = bounds.y;
        int x2 = bounds.x + bounds.width - 1;
        int y2 = bounds.y + bounds.height - 1;

        // Calculate visual box edges (selection box goes from x1 to x2+1 in canvas space)
        double boxRight = x2 + 1;
        double boxBottom = y2 + 1;
        double midX = (x1 + boxRight) / 2.0;
        double midY = (y1 + boxBottom) / 2.0;

        // Corner handles - positioned on selection box corners with precise hit radius
        handles.add(new TransformHandle(TransformHandle.Type.CORNER_TOP_LEFT, x1, y1, CORNER_HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.CORNER_TOP_RIGHT, boxRight, y1, CORNER_HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.CORNER_BOTTOM_LEFT, x1, boxBottom, CORNER_HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.CORNER_BOTTOM_RIGHT, boxRight, boxBottom, CORNER_HANDLE_RADIUS));

        // Edge handles - positioned on selection box edge midpoints with precise hit radius
        handles.add(new TransformHandle(TransformHandle.Type.EDGE_TOP, midX, y1, EDGE_HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.EDGE_RIGHT, boxRight, midY, EDGE_HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.EDGE_BOTTOM, midX, boxBottom, EDGE_HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.EDGE_LEFT, x1, midY, EDGE_HANDLE_RADIUS));

        // Rotation handle - offset is constant in screen space, so divide by zoom to get canvas space offset
        double canvasSpaceOffset = ROTATION_HANDLE_SCREEN_OFFSET / Math.max(0.1, currentZoom);
        handles.add(new TransformHandle(TransformHandle.Type.ROTATION, midX, y1 - canvasSpaceOffset, ROTATION_HANDLE_RADIUS));

        // Center handle (for moving) - fixed reasonable size
        handles.add(new TransformHandle(TransformHandle.Type.CENTER, midX, midY, CENTER_HANDLE_RADIUS));
    }

    /**
     * Finds the nearest handle to the given point using distance-based selection.
     * Returns the handle only if the mouse is within its hit radius.
     * Priority: Always selects the closest valid handle (distance-based)
     *
     * @param x Mouse x coordinate in canvas space
     * @param y Mouse y coordinate in canvas space
     * @param includeCenter Whether to include the center handle in the search
     * @return The nearest handle within range, or null if no handle is close enough
     */
    private TransformHandle findNearestHandle(double x, double y, boolean includeCenter) {
        TransformHandle nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        // Find the closest handle that contains the point
        for (TransformHandle handle : handles) {
            // Skip center handle if not included
            if (!includeCenter && handle.isCenter()) {
                continue;
            }

            if (handle.contains(x, y)) {
                double distance = calculateDistance(x, y, handle.getX(), handle.getY());

                // Always prefer the closest handle
                if (distance < nearestDistance) {
                    nearest = handle;
                    nearestDistance = distance;
                }
            }
        }

        return nearest;
    }

    /**
     * Calculates Euclidean distance between two points.
     *
     * @param x1 First point x
     * @param y1 First point y
     * @param x2 Second point x
     * @param y2 Second point y
     * @return Distance between the points
     */
    private double calculateDistance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private TransformHandle findCenterHandle() {
        for (TransformHandle handle : handles) {
            if (handle.isCenter()) {
                return handle;
            }
        }
        return null;
    }

    // ==================== HELPER METHODS ====================

    private int[] getAnchorForCorner(TransformHandle.Type cornerType) {
        return switch (cornerType) {
            case CORNER_TOP_LEFT -> new int[]{originalX2, originalY2};
            case CORNER_TOP_RIGHT -> new int[]{originalX1, originalY2};
            case CORNER_BOTTOM_LEFT -> new int[]{originalX2, originalY1};
            case CORNER_BOTTOM_RIGHT -> new int[]{originalX1, originalY1};
            default -> throw new IllegalArgumentException("Not a corner: " + cornerType);
        };
    }

    private int[] getCornerPosition(TransformHandle.Type cornerType, int x1, int y1, int x2, int y2) {
        return switch (cornerType) {
            case CORNER_TOP_LEFT -> new int[]{x1, y1};
            case CORNER_TOP_RIGHT -> new int[]{x2, y1};
            case CORNER_BOTTOM_LEFT -> new int[]{x1, y2};
            case CORNER_BOTTOM_RIGHT -> new int[]{x2, y2};
            default -> throw new IllegalArgumentException("Not a corner: " + cornerType);
        };
    }

    private void renderPreviewBounds(ImDrawList drawList, float canvasX, float canvasY, float zoom) {
        int color = imgui.ImColor.rgba(255, 200, 0, 150); // Orange semi-transparent

        // Render preview box matching selection box coordinate system
        float x1 = canvasX + previewX1 * zoom;
        float y1 = canvasY + previewY1 * zoom;
        float x2 = canvasX + (previewX2 + 1) * zoom;  // +1 to match selection rendering
        float y2 = canvasY + (previewY2 + 1) * zoom;  // +1 to match selection rendering

        drawList.addRect(x1, y1, x2, y2, color, 0.0f, 0, 2.0f);
    }

    /**
     * Renders the rotated free selection preview during rotation.
     */
    private void renderRotatedFreeSelectionPreview(ImDrawList drawList, float canvasX, float canvasY, float zoom) {
        if (rotatedFreeSelectionPreview == null) {
            return;
        }

        int previewColor = imgui.ImColor.rgba(100, 150, 255, 100); // Blue semi-transparent

        // Render each pixel in the rotated selection
        for (FreeSelection.Pixel pixel : rotatedFreeSelectionPreview.getPixels()) {
            float x1 = canvasX + pixel.x * zoom;
            float y1 = canvasY + pixel.y * zoom;
            float x2 = x1 + zoom;
            float y2 = y1 + zoom;

            drawList.addRectFilled(x1, y1, x2, y2, previewColor);
        }
    }

    // ==================== ROTATION GEOMETRY CALCULATION ====================

    /**
     * Rotates a point around a center by the given angle.
     *
     * @param x Point x coordinate
     * @param y Point y coordinate
     * @param centerX Center x coordinate
     * @param centerY Center y coordinate
     * @param angleDegrees Rotation angle in degrees
     * @return Array [rotatedX, rotatedY]
     */
    private double[] rotatePoint(double x, double y, double centerX, double centerY, double angleDegrees) {
        double angleRad = Math.toRadians(angleDegrees);
        double cos = Math.cos(angleRad);
        double sin = Math.sin(angleRad);

        double dx = x - centerX;
        double dy = y - centerY;

        double rotatedX = centerX + dx * cos - dy * sin;
        double rotatedY = centerY + dx * sin + dy * cos;

        return new double[]{rotatedX, rotatedY};
    }

    /**
     * Calculates the 4 rotated corner positions of the selection.
     * Stores result in rotatedCorners array: [x1,y1, x2,y1, x2,y2, x1,y2]
     */
    private void calculateRotatedCorners() {
        // Calculate center using visual box coordinates
        double boxRight = originalX2 + 1;
        double boxBottom = originalY2 + 1;
        double centerX = (originalX1 + boxRight) / 2.0;
        double centerY = (originalY1 + boxBottom) / 2.0;

        // Rotate all 4 corners of the visual box
        double[] topLeft = rotatePoint(originalX1, originalY1, centerX, centerY, rotationAngleDegrees);
        double[] topRight = rotatePoint(boxRight, originalY1, centerX, centerY, rotationAngleDegrees);
        double[] bottomRight = rotatePoint(boxRight, boxBottom, centerX, centerY, rotationAngleDegrees);
        double[] bottomLeft = rotatePoint(originalX1, boxBottom, centerX, centerY, rotationAngleDegrees);

        rotatedCorners = new double[]{
            topLeft[0], topLeft[1],
            topRight[0], topRight[1],
            bottomRight[0], bottomRight[1],
            bottomLeft[0], bottomLeft[1]
        };
    }

    /**
     * Generates handles at rotated positions for live rotation visualization.
     */
    private void generateRotatedHandles() {
        rotatedHandles = new ArrayList<>();

        if (rotatedCorners == null) {
            return;
        }

        // Calculate center using visual box coordinates
        double boxRight = originalX2 + 1;
        double boxBottom = originalY2 + 1;
        double centerX = (originalX1 + boxRight) / 2.0;
        double centerY = (originalY1 + boxBottom) / 2.0;

        // Extract rotated corners
        double x1 = rotatedCorners[0];
        double y1 = rotatedCorners[1];
        double x2 = rotatedCorners[2];
        double y2 = rotatedCorners[3];
        double x3 = rotatedCorners[4];
        double y3 = rotatedCorners[5];
        double x4 = rotatedCorners[6];
        double y4 = rotatedCorners[7];

        // Corner handles at rotated positions with precise hit radius
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.CORNER_TOP_LEFT, x1, y1, CORNER_HANDLE_RADIUS));
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.CORNER_TOP_RIGHT, x2, y2, CORNER_HANDLE_RADIUS));
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.CORNER_BOTTOM_RIGHT, x3, y3, CORNER_HANDLE_RADIUS));
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.CORNER_BOTTOM_LEFT, x4, y4, CORNER_HANDLE_RADIUS));

        // Edge handles at rotated midpoints with precise hit radius
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.EDGE_TOP, (x1 + x2) / 2.0, (y1 + y2) / 2.0, EDGE_HANDLE_RADIUS));
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.EDGE_RIGHT, (x2 + x3) / 2.0, (y2 + y3) / 2.0, EDGE_HANDLE_RADIUS));
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.EDGE_BOTTOM, (x3 + x4) / 2.0, (y3 + y4) / 2.0, EDGE_HANDLE_RADIUS));
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.EDGE_LEFT, (x4 + x1) / 2.0, (y4 + y1) / 2.0, EDGE_HANDLE_RADIUS));

        // Rotation handle - rotate the top center point (using zoom-adjusted offset)
        double canvasSpaceOffset = ROTATION_HANDLE_SCREEN_OFFSET / Math.max(0.1, currentZoom);
        double topCenterY = originalY1 - canvasSpaceOffset;
        double[] rotatedTopCenter = rotatePoint(centerX, topCenterY, centerX, centerY, rotationAngleDegrees);
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.ROTATION, rotatedTopCenter[0], rotatedTopCenter[1], ROTATION_HANDLE_RADIUS));
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
        handles.clear();
        hoveredHandle = null;
        activeHandle = null;
        completedCommand = null;
        updatedSelection = null;
        transformPerformed = false;
        shiftHeld = false;
        uniformScaling = false; // Reset uniform scaling option
        rotatedCorners = null;
        rotatedHandles = null;
        rotatedFreeSelectionPreview = null;
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
