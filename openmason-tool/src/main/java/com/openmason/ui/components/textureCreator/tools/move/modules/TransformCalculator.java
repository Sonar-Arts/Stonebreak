package com.openmason.ui.components.textureCreator.tools.move.modules;

import com.openmason.ui.components.textureCreator.selection.SelectionRegion;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Calculates transformation state based on handle dragging.
 * Pure functional approach - no side effects.
 */
public class TransformCalculator {

    /**
     * Calculates the new transform state based on dragging a handle.
     *
     * @param handleType The handle being dragged
     * @param dragStart Starting point of drag (canvas coordinates)
     * @param dragCurrent Current point of drag (canvas coordinates)
     * @param originalSelection The original selection before any transformation
     * @param currentTransform The current transformation state
     * @param maintainAspectRatio Whether to maintain aspect ratio (for corner handles)
     * @return New transform state
     */
    public TransformState calculateTransform(HandleType handleType,
                                             Point dragStart,
                                             Point dragCurrent,
                                             SelectionRegion originalSelection,
                                             TransformState currentTransform,
                                             boolean maintainAspectRatio) {

        if (handleType == null) {
            return currentTransform;
        }

        // Handle rotation separately
        if (handleType.isRotation()) {
            return calculateRotation(dragStart, dragCurrent, originalSelection, currentTransform);
        }

        // Calculate drag delta
        int dx = dragCurrent.x - dragStart.x;
        int dy = dragCurrent.y - dragStart.y;

        // Get original bounds
        Rectangle originalBounds = originalSelection.getBounds();
        Point pivot = new Point(
                originalBounds.x + originalBounds.width / 2,
                originalBounds.y + originalBounds.height / 2
        );

        // For dragging the selection body (no handle), just translate
        if (!handleType.affectsScaleX() && !handleType.affectsScaleY() && !handleType.isRotation()) {
            return currentTransform.toBuilder()
                    .translate(dx, dy)
                    .pivot(pivot)
                    .build();
        }

        // Calculate scale based on handle type
        return calculateScale(handleType, dragStart, dragCurrent, originalBounds,
                currentTransform, maintainAspectRatio, pivot);
    }

    /**
     * Calculates translation-only transform (for dragging selection body).
     */
    public TransformState calculateTranslation(Point dragStart, Point dragCurrent,
                                               SelectionRegion originalSelection,
                                               TransformState currentTransform) {
        int dx = dragCurrent.x - dragStart.x;
        int dy = dragCurrent.y - dragStart.y;

        Rectangle originalBounds = originalSelection.getBounds();
        Point pivot = new Point(
                originalBounds.x + originalBounds.width / 2,
                originalBounds.y + originalBounds.height / 2
        );

        return currentTransform.toBuilder()
                .translate(
                        currentTransform.getTranslateX() + dx,
                        currentTransform.getTranslateY() + dy
                )
                .pivot(pivot)
                .build();
    }

    private TransformState calculateScale(HandleType handleType,
                                          Point dragStart,
                                          Point dragCurrent,
                                          Rectangle originalBounds,
                                          TransformState currentTransform,
                                          boolean maintainAspectRatio,
                                          Point pivot) {

        // Calculate cumulative drag delta from original start point
        int dx = dragCurrent.x - dragStart.x;
        int dy = dragCurrent.y - dragStart.y;

        // Start with identity scale
        double scaleX = 1.0;
        double scaleY = 1.0;

        // Calculate scale based on handle direction and drag distance
        // Formula: scale = (originalSize + dragDelta) / originalSize = 1 + (dragDelta / originalSize)
        switch (handleType) {
            case TOP_LEFT:
                // Dragging left/up shrinks (negative dx/dy), dragging right/down grows
                scaleX = 1.0 + (double)(-dx * 2) / originalBounds.width;  // *2 because handle moves half the distance
                scaleY = 1.0 + (double)(-dy * 2) / originalBounds.height;
                break;
            case TOP_RIGHT:
                // Dragging right grows X, dragging up shrinks Y
                scaleX = 1.0 + (double)(dx * 2) / originalBounds.width;
                scaleY = 1.0 + (double)(-dy * 2) / originalBounds.height;
                break;
            case BOTTOM_LEFT:
                // Dragging left shrinks X, dragging down grows Y
                scaleX = 1.0 + (double)(-dx * 2) / originalBounds.width;
                scaleY = 1.0 + (double)(dy * 2) / originalBounds.height;
                break;
            case BOTTOM_RIGHT:
                // Dragging right grows X, dragging down grows Y
                scaleX = 1.0 + (double)(dx * 2) / originalBounds.width;
                scaleY = 1.0 + (double)(dy * 2) / originalBounds.height;
                break;
            case TOP_CENTER:
                // Dragging up shrinks Y, dragging down grows Y
                scaleY = 1.0 + (double)(-dy * 2) / originalBounds.height;
                break;
            case BOTTOM_CENTER:
                // Dragging down grows Y, dragging up shrinks Y
                scaleY = 1.0 + (double)(dy * 2) / originalBounds.height;
                break;
            case MIDDLE_LEFT:
                // Dragging left shrinks X, dragging right grows X
                scaleX = 1.0 + (double)(-dx * 2) / originalBounds.width;
                break;
            case MIDDLE_RIGHT:
                // Dragging right grows X, dragging left shrinks X
                scaleX = 1.0 + (double)(dx * 2) / originalBounds.width;
                break;
        }

        // Maintain aspect ratio for corner handles if requested
        if (maintainAspectRatio && handleType.isCorner()) {
            double avgScale = (scaleX + scaleY) / 2.0;
            scaleX = avgScale;
            scaleY = avgScale;
        }

        // Prevent negative or zero scaling
        scaleX = Math.max(0.1, scaleX);
        scaleY = Math.max(0.1, scaleY);

        // Prevent excessive scaling
        scaleX = Math.min(10.0, scaleX);
        scaleY = Math.min(10.0, scaleY);

        return currentTransform.toBuilder()
                .scale(scaleX, scaleY)
                .pivot(pivot)
                .build();
    }

    private TransformState calculateRotation(Point dragStart,
                                             Point dragCurrent,
                                             SelectionRegion originalSelection,
                                             TransformState currentTransform) {

        // Get center of selection as rotation pivot
        Rectangle bounds = originalSelection.getBounds();
        Point center = new Point(
                bounds.x + bounds.width / 2,
                bounds.y + bounds.height / 2
        );

        // Calculate angles from center to drag points
        double angleStart = Math.atan2(dragStart.y - center.y, dragStart.x - center.x);
        double angleCurrent = Math.atan2(dragCurrent.y - center.y, dragCurrent.x - center.x);

        // Calculate rotation delta in degrees
        double deltaRadians = angleCurrent - angleStart;
        double deltaDegrees = Math.toDegrees(deltaRadians);

        // Add to current rotation
        double newRotation = currentTransform.getRotationDegrees() + deltaDegrees;

        // Normalize to 0-360 range
        while (newRotation < 0) newRotation += 360;
        while (newRotation >= 360) newRotation -= 360;

        return currentTransform.toBuilder()
                .rotate(newRotation)
                .pivot(center)
                .build();
    }
}
