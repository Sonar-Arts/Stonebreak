package com.openmason.ui.components.textureCreator.tools.move.modules;

import com.openmason.ui.components.textureCreator.canvas.CanvasState;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Detects which transformation handle (if any) is under the cursor.
 * Uses screen coordinates for hit testing.
 */
public class HandleDetector {
    private static final int HANDLE_SIZE = 8; // Size of handle squares in screen pixels
    private static final int HANDLE_HIT_RADIUS = 12; // Hit detection radius
    private static final int ROTATION_HANDLE_OFFSET = 20; // Distance above selection in screen pixels

    /**
     * Detects which handle is at the given screen coordinates.
     *
     * @param screenX Screen X coordinate
     * @param screenY Screen Y coordinate
     * @param selection The current selection region (canvas coordinates)
     * @param canvasState Canvas state for coordinate conversion
     * @param currentTransform Current transformation state
     * @param canvasDisplayX Canvas display area X offset
     * @param canvasDisplayY Canvas display area Y offset
     * @return The HandleType at the cursor, or null if none
     */
    public HandleType getHandleAt(float screenX, float screenY, SelectionRegion selection,
                                   CanvasState canvasState, TransformState currentTransform,
                                   float canvasDisplayX, float canvasDisplayY) {
        if (selection == null || selection.isEmpty()) {
            return null;
        }

        // Get selection bounds in canvas coordinates
        Rectangle canvasBounds = selection.getBounds();

        // Apply current transformation to bounds
        Rectangle transformedBounds = applyTransformToBounds(canvasBounds, currentTransform);

        // Convert corners to screen coordinates
        float[] screenCoords = new float[2];

        canvasState.canvasToScreenCoords(transformedBounds.x, transformedBounds.y,
                canvasDisplayX, canvasDisplayY, screenCoords);
        Point topLeft = new Point((int)screenCoords[0], (int)screenCoords[1]);

        canvasState.canvasToScreenCoords(transformedBounds.x + transformedBounds.width,
                transformedBounds.y, canvasDisplayX, canvasDisplayY, screenCoords);
        Point topRight = new Point((int)screenCoords[0], (int)screenCoords[1]);

        canvasState.canvasToScreenCoords(transformedBounds.x,
                transformedBounds.y + transformedBounds.height,
                canvasDisplayX, canvasDisplayY, screenCoords);
        Point bottomLeft = new Point((int)screenCoords[0], (int)screenCoords[1]);

        canvasState.canvasToScreenCoords(transformedBounds.x + transformedBounds.width,
                transformedBounds.y + transformedBounds.height,
                canvasDisplayX, canvasDisplayY, screenCoords);
        Point bottomRight = new Point((int)screenCoords[0], (int)screenCoords[1]);

        // Calculate edge midpoints
        Point topCenter = midpoint(topLeft, topRight);
        Point bottomCenter = midpoint(bottomLeft, bottomRight);
        Point middleLeft = midpoint(topLeft, bottomLeft);
        Point middleRight = midpoint(topRight, bottomRight);

        // Calculate rotation handle position (above top center)
        Point rotationHandle = new Point(topCenter.x, topCenter.y - ROTATION_HANDLE_OFFSET);

        // Check rotation handle first (highest priority)
        if (isNear(screenX, screenY, rotationHandle)) {
            return HandleType.ROTATION;
        }

        // Check corner handles
        if (isNear(screenX, screenY, topLeft)) return HandleType.TOP_LEFT;
        if (isNear(screenX, screenY, topRight)) return HandleType.TOP_RIGHT;
        if (isNear(screenX, screenY, bottomLeft)) return HandleType.BOTTOM_LEFT;
        if (isNear(screenX, screenY, bottomRight)) return HandleType.BOTTOM_RIGHT;

        // Check edge handles
        if (isNear(screenX, screenY, topCenter)) return HandleType.TOP_CENTER;
        if (isNear(screenX, screenY, bottomCenter)) return HandleType.BOTTOM_CENTER;
        if (isNear(screenX, screenY, middleLeft)) return HandleType.MIDDLE_LEFT;
        if (isNear(screenX, screenY, middleRight)) return HandleType.MIDDLE_RIGHT;

        return null;
    }

    /**
     * Checks if the given point is inside the selection bounds (for detecting clicks on selection body).
     */
    public boolean isInsideSelection(float screenX, float screenY, SelectionRegion selection,
                                     CanvasState canvasState, TransformState currentTransform,
                                     float canvasDisplayX, float canvasDisplayY) {
        if (selection == null || selection.isEmpty()) {
            return false;
        }

        // Convert screen to canvas coordinates
        int[] canvasCoords = new int[2];
        canvasState.screenToCanvasCoords(screenX, screenY, canvasDisplayX, canvasDisplayY, canvasCoords);
        Point canvasPoint = new Point(canvasCoords[0], canvasCoords[1]);

        // Apply inverse transform to point
        Point untransformedPoint = applyInverseTransform(canvasPoint, currentTransform);

        // Check if point is in original selection
        return selection.contains(untransformedPoint.x, untransformedPoint.y);
    }

    /**
     * Calculates handle positions in screen coordinates for rendering.
     */
    public HandlePositions calculateHandlePositions(SelectionRegion selection,
                                                     CanvasState canvasState,
                                                     TransformState currentTransform,
                                                     float canvasDisplayX,
                                                     float canvasDisplayY) {
        if (selection == null || selection.isEmpty()) {
            return null;
        }

        Rectangle canvasBounds = selection.getBounds();
        Rectangle transformedBounds = applyTransformToBounds(canvasBounds, currentTransform);

        float[] screenCoords = new float[2];

        canvasState.canvasToScreenCoords(transformedBounds.x, transformedBounds.y,
                canvasDisplayX, canvasDisplayY, screenCoords);
        Point topLeft = new Point((int)screenCoords[0], (int)screenCoords[1]);

        canvasState.canvasToScreenCoords(transformedBounds.x + transformedBounds.width,
                transformedBounds.y, canvasDisplayX, canvasDisplayY, screenCoords);
        Point topRight = new Point((int)screenCoords[0], (int)screenCoords[1]);

        canvasState.canvasToScreenCoords(transformedBounds.x,
                transformedBounds.y + transformedBounds.height,
                canvasDisplayX, canvasDisplayY, screenCoords);
        Point bottomLeft = new Point((int)screenCoords[0], (int)screenCoords[1]);

        canvasState.canvasToScreenCoords(transformedBounds.x + transformedBounds.width,
                transformedBounds.y + transformedBounds.height,
                canvasDisplayX, canvasDisplayY, screenCoords);
        Point bottomRight = new Point((int)screenCoords[0], (int)screenCoords[1]);

        Point topCenter = midpoint(topLeft, topRight);
        Point bottomCenter = midpoint(bottomLeft, bottomRight);
        Point middleLeft = midpoint(topLeft, bottomLeft);
        Point middleRight = midpoint(topRight, bottomRight);
        Point rotationHandle = new Point(topCenter.x, topCenter.y - ROTATION_HANDLE_OFFSET);

        return new HandlePositions(topLeft, topRight, bottomLeft, bottomRight,
                topCenter, bottomCenter, middleLeft, middleRight, rotationHandle);
    }

    private boolean isNear(float x, float y, Point handle) {
        float dx = x - handle.x;
        float dy = y - handle.y;
        return (dx * dx + dy * dy) <= (HANDLE_HIT_RADIUS * HANDLE_HIT_RADIUS);
    }

    private Point midpoint(Point p1, Point p2) {
        return new Point((p1.x + p2.x) / 2, (p1.y + p2.y) / 2);
    }

    private Rectangle applyTransformToBounds(Rectangle bounds, TransformState transform) {
        // Apply scale to dimensions
        int scaledWidth = (int) Math.round(bounds.width * transform.getScaleX());
        int scaledHeight = (int) Math.round(bounds.height * transform.getScaleY());

        // Calculate offset due to scaling (scale happens from center)
        int scaleOffsetX = (bounds.width - scaledWidth) / 2;
        int scaleOffsetY = (bounds.height - scaledHeight) / 2;

        // Apply translation
        int translatedX = bounds.x + transform.getTranslateX() + scaleOffsetX;
        int translatedY = bounds.y + transform.getTranslateY() + scaleOffsetY;

        // Note: Rotation is not applied to bounds rectangle (would need oriented bounding box)
        // Rotation is applied to individual pixels during transformation
        return new Rectangle(translatedX, translatedY, scaledWidth, scaledHeight);
    }

    private Point applyInverseTransform(Point point, TransformState transform) {
        // Reverse translation
        int x = point.x - transform.getTranslateX();
        int y = point.y - transform.getTranslateY();

        // Get original selection center
        Rectangle bounds = new Rectangle(0, 0, 1, 1); // Placeholder, will be calculated from selection

        // Reverse scale (scale from center)
        if (transform.getScaleX() != 1.0 || transform.getScaleY() != 1.0) {
            // For simplicity, just reverse the scale uniformly
            // This is an approximation for hit testing purposes
            x = (int) Math.round(x / transform.getScaleX());
            y = (int) Math.round(y / transform.getScaleY());
        }

        // Note: Rotation inverse not implemented (complex for hit testing)
        // For now, rotation is only applied to pixels, not selection bounds
        return new Point(x, y);
    }

    public int getHandleSize() {
        return HANDLE_SIZE;
    }

    /**
     * Data class holding all handle positions in screen coordinates.
     */
    public static class HandlePositions {
        public final Point topLeft, topRight, bottomLeft, bottomRight;
        public final Point topCenter, bottomCenter, middleLeft, middleRight;
        public final Point rotation;

        public HandlePositions(Point topLeft, Point topRight, Point bottomLeft, Point bottomRight,
                               Point topCenter, Point bottomCenter, Point middleLeft, Point middleRight,
                               Point rotation) {
            this.topLeft = topLeft;
            this.topRight = topRight;
            this.bottomLeft = bottomLeft;
            this.bottomRight = bottomRight;
            this.topCenter = topCenter;
            this.bottomCenter = bottomCenter;
            this.middleLeft = middleLeft;
            this.middleRight = middleRight;
            this.rotation = rotation;
        }

        public Point getHandle(HandleType type) {
            switch (type) {
                case TOP_LEFT: return topLeft;
                case TOP_RIGHT: return topRight;
                case BOTTOM_LEFT: return bottomLeft;
                case BOTTOM_RIGHT: return bottomRight;
                case TOP_CENTER: return topCenter;
                case BOTTOM_CENTER: return bottomCenter;
                case MIDDLE_LEFT: return middleLeft;
                case MIDDLE_RIGHT: return middleRight;
                case ROTATION: return rotation;
                default: return null;
            }
        }
    }
}
