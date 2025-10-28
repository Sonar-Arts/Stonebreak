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

        // Calculate pivot point based on handle type (which edge/corner should stay fixed)
        Point pivot = calculatePivot(handleType, originalBounds);

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

        // No offsets needed - the pivot point handles keeping the opposite edge/corner fixed!
        int offsetX = 0;
        int offsetY = 0;

        // Calculate scale based on handle direction and drag distance
        switch (handleType) {
            case TOP_LEFT:
            case BOTTOM_LEFT:
            case MIDDLE_LEFT:
                // Dragging left edge - scale X by negative delta
                scaleX = 1.0 - (double)dx / originalBounds.width;
                break;
            case TOP_RIGHT:
            case BOTTOM_RIGHT:
            case MIDDLE_RIGHT:
                // Dragging right edge - scale X by positive delta
                scaleX = 1.0 + (double)dx / originalBounds.width;
                break;
        }

        switch (handleType) {
            case TOP_LEFT:
            case TOP_RIGHT:
            case TOP_CENTER:
                // Dragging top edge - scale Y by negative delta
                scaleY = 1.0 - (double)dy / originalBounds.height;
                break;
            case BOTTOM_LEFT:
            case BOTTOM_RIGHT:
            case BOTTOM_CENTER:
                // Dragging bottom edge - scale Y by positive delta
                scaleY = 1.0 + (double)dy / originalBounds.height;
                break;
        }

        // Maintain aspect ratio for corner handles if requested
        if (maintainAspectRatio && handleType.isCorner()) {
            // Use the larger absolute scale to maintain aspect ratio
            double avgScale = (Math.abs(scaleX - 1.0) > Math.abs(scaleY - 1.0)) ? scaleX : scaleY;
            scaleX = avgScale;
            scaleY = avgScale;
            // No offset recalculation needed - pivot handles everything!
        }

        // Enforce minimum size constraint (1x1 pixel)
        double minScaleX = 1.0 / originalBounds.width;
        double minScaleY = 1.0 / originalBounds.height;
        scaleX = Math.max(minScaleX, scaleX);
        scaleY = Math.max(minScaleY, scaleY);

        // Prevent excessive scaling
        scaleX = Math.min(10.0, scaleX);
        scaleY = Math.min(10.0, scaleY);

        // Apply translation offset to keep opposite corner fixed
        return currentTransform.toBuilder()
                .scale(scaleX, scaleY)
                .translate(offsetX, offsetY)
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

    /**
     * Calculates the pivot point based on which handle is being dragged.
     * The pivot is the fixed point that doesn't move during scaling.
     * Returns absolute coordinates.
     */
    private Point calculatePivot(HandleType handleType, Rectangle bounds) {
        int pivotX, pivotY;

        switch (handleType) {
            case TOP_LEFT:
                // Bottom-right corner stays fixed
                pivotX = bounds.x + bounds.width;
                pivotY = bounds.y + bounds.height;
                break;
            case TOP_RIGHT:
                // Bottom-left corner stays fixed
                pivotX = bounds.x;
                pivotY = bounds.y + bounds.height;
                break;
            case BOTTOM_LEFT:
                // Top-right corner stays fixed
                pivotX = bounds.x + bounds.width;
                pivotY = bounds.y;
                break;
            case BOTTOM_RIGHT:
                // Top-left corner stays fixed
                pivotX = bounds.x;
                pivotY = bounds.y;
                break;
            case TOP_CENTER:
                // Bottom edge stays fixed
                pivotX = bounds.x + bounds.width / 2;
                pivotY = bounds.y + bounds.height;
                break;
            case BOTTOM_CENTER:
                // Top edge stays fixed
                pivotX = bounds.x + bounds.width / 2;
                pivotY = bounds.y;
                break;
            case MIDDLE_LEFT:
                // Right edge stays fixed
                pivotX = bounds.x + bounds.width;
                pivotY = bounds.y + bounds.height / 2;
                break;
            case MIDDLE_RIGHT:
                // Left edge stays fixed
                pivotX = bounds.x;
                pivotY = bounds.y + bounds.height / 2;
                break;
            default:
                // Center for anything else
                pivotX = bounds.x + bounds.width / 2;
                pivotY = bounds.y + bounds.height / 2;
                break;
        }

        return new Point(pivotX, pivotY);
    }
}
