package com.openmason.ui.components.textureCreator.tools.movetool.transform;

import com.openmason.ui.components.textureCreator.selection.FreeSelection;
import com.openmason.ui.components.textureCreator.selection.SelectionRegion;
import com.openmason.ui.components.textureCreator.tools.movetool.state.TransformState;
import com.openmason.ui.components.textureCreator.transform.TransformHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculates transform operations (move, scale, stretch, rotate).
 * Pure calculation logic - takes current mouse position and state, updates preview coordinates.
 * Follows SOLID (Single Responsibility), KISS (direct math), and DRY principles.
 *
 * @author Open Mason Team
 */
public class TransformCalculator {

    /**
     * Updates moving transformation - simple translation.
     *
     * @param state Transform state to update
     * @param currentX Current mouse X position
     * @param currentY Current mouse Y position
     */
    public void updateMoving(TransformState state, int currentX, int currentY) {
        int deltaX = currentX - state.getDragStartX();
        int deltaY = currentY - state.getDragStartY();

        state.setPreviewX1(state.getOriginalX1() + deltaX);
        state.setPreviewY1(state.getOriginalY1() + deltaY);
        state.setPreviewX2(state.getOriginalX2() + deltaX);
        state.setPreviewY2(state.getOriginalY2() + deltaY);
    }

    /**
     * Updates corner scaling transformation with optional proportional scaling.
     *
     * @param state Transform state to update
     * @param currentX Current mouse X position
     * @param currentY Current mouse Y position
     * @param activeHandle The corner handle being dragged
     * @param proportional Whether to force proportional scaling
     */
    public void updateScaling(TransformState state, int currentX, int currentY,
                              TransformHandle activeHandle, boolean proportional) {
        // Get anchor point (opposite corner)
        int[] anchor = getAnchorForCorner(activeHandle.getType(), state);
        int anchorX = anchor[0];
        int anchorY = anchor[1];

        // Store anchor for command creation
        state.setScaleAnchorX(anchorX);
        state.setScaleAnchorY(anchorY);

        // Get original dragged corner position
        int[] origCorner = getCornerPosition(activeHandle.getType(),
            state.getOriginalX1(), state.getOriginalY1(),
            state.getOriginalX2(), state.getOriginalY2());
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

        // Proportional scaling (use absolute values for comparison)
        if (proportional) {
            double uniformScale = Math.max(Math.abs(scaleX), Math.abs(scaleY));
            // Preserve signs
            scaleX = uniformScale * Math.signum(scaleX);
            scaleY = uniformScale * Math.signum(scaleY);
        }

        // Store scale factors for command creation
        state.setScaleFactorX(scaleX);
        state.setScaleFactorY(scaleY);

        // Calculate new bounds
        int newX1 = anchorX + (int) Math.round((state.getOriginalX1() - anchorX) * scaleX);
        int newY1 = anchorY + (int) Math.round((state.getOriginalY1() - anchorY) * scaleY);
        int newX2 = anchorX + (int) Math.round((state.getOriginalX2() - anchorX) * scaleX);
        int newY2 = anchorY + (int) Math.round((state.getOriginalY2() - anchorY) * scaleY);

        // Normalize (ensure x1 < x2, y1 < y2)
        state.setPreviewX1(Math.min(newX1, newX2));
        state.setPreviewY1(Math.min(newY1, newY2));
        state.setPreviewX2(Math.max(newX1, newX2));
        state.setPreviewY2(Math.max(newY1, newY2));
    }

    /**
     * Updates edge stretching transformation with optional uniform scaling.
     *
     * @param state Transform state to update
     * @param currentX Current mouse X position
     * @param currentY Current mouse Y position
     * @param activeHandle The edge handle being dragged
     * @param uniform Whether to force uniform scaling on both axes
     */
    public void updateStretching(TransformState state, int currentX, int currentY,
                                 TransformHandle activeHandle, boolean uniform) {
        // Start with original bounds
        state.setPreviewX1(state.getOriginalX1());
        state.setPreviewY1(state.getOriginalY1());
        state.setPreviewX2(state.getOriginalX2());
        state.setPreviewY2(state.getOriginalY2());

        // Original dimensions
        int originalWidth = state.getOriginalX2() - state.getOriginalX1();
        int originalHeight = state.getOriginalY2() - state.getOriginalY1();

        // Calculate scale factor for the dragged edge
        double primaryScaleFactor = calculateEdgeScaleFactor(state, currentX, currentY, activeHandle);

        // Apply uniform scaling if enabled
        if (uniform) {
            applyUniformEdgeScaling(state, primaryScaleFactor, activeHandle);
        } else {
            applyNonUniformEdgeScaling(state, primaryScaleFactor, activeHandle);
        }

        // Normalize
        normalizePreviewBounds(state);
    }

    /**
     * Updates rotation transformation with optional angle snapping.
     *
     * @param state Transform state to update
     * @param currentX Current mouse X position
     * @param currentY Current mouse Y position
     * @param currentSelection Current selection region
     * @param snapToIncrements Whether to snap to 15-degree increments
     */
    public void updateRotating(TransformState state, int currentX, int currentY,
                               SelectionRegion currentSelection, boolean snapToIncrements) {
        // Calculate center using visual box coordinates
        double[] center = GeometryHelper.calculateCenter(
            state.getOriginalX1(), state.getOriginalY1(),
            state.getOriginalX2(), state.getOriginalY2());
        double centerX = center[0];
        double centerY = center[1];

        double angleStart = Math.toDegrees(Math.atan2(
            state.getDragStartY() - centerY,
            state.getDragStartX() - centerX));
        double angleCurrent = Math.toDegrees(Math.atan2(
            currentY - centerY,
            currentX - centerX));

        double rotationAngle = angleCurrent - angleStart;

        // Snap to 15° increments if requested
        if (snapToIncrements) {
            rotationAngle = Math.round(rotationAngle / 15.0) * 15.0;
        }

        // Snap to cardinal angles (0, 90, 180, 270) within 5° threshold
        rotationAngle = snapToCardinalAngles(rotationAngle);

        state.setRotationAngleDegrees(rotationAngle);

        // Calculate rotated geometry for live visualization
        double[] rotatedCorners = GeometryHelper.calculateRotatedCorners(
            state.getOriginalX1(), state.getOriginalY1(),
            state.getOriginalX2(), state.getOriginalY2(),
            rotationAngle);
        state.setRotatedCorners(rotatedCorners);

        // Generate rotated handles
        List<TransformHandle> rotatedHandles = generateRotatedHandles(state, rotatedCorners);
        state.setRotatedHandles(rotatedHandles);

        // Generate rotated free selection preview if applicable
        if (currentSelection instanceof FreeSelection) {
            FreeSelection freeSelection = (FreeSelection) currentSelection;
            FreeSelection rotatedPreview = freeSelection.rotate(rotationAngle);
            state.setRotatedFreeSelectionPreview(rotatedPreview);
        } else {
            state.setRotatedFreeSelectionPreview(null);
        }
    }

    // ==================== PRIVATE HELPER METHODS ====================

    private int[] getAnchorForCorner(TransformHandle.Type cornerType, TransformState state) {
        return switch (cornerType) {
            case CORNER_TOP_LEFT -> new int[]{state.getOriginalX2(), state.getOriginalY2()};
            case CORNER_TOP_RIGHT -> new int[]{state.getOriginalX1(), state.getOriginalY2()};
            case CORNER_BOTTOM_LEFT -> new int[]{state.getOriginalX2(), state.getOriginalY1()};
            case CORNER_BOTTOM_RIGHT -> new int[]{state.getOriginalX1(), state.getOriginalY1()};
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

    private double calculateEdgeScaleFactor(TransformState state, int currentX, int currentY,
                                            TransformHandle activeHandle) {
        int originalWidth = state.getOriginalX2() - state.getOriginalX1();
        int originalHeight = state.getOriginalY2() - state.getOriginalY1();

        return switch (activeHandle.getType()) {
            case EDGE_TOP -> {
                int deltaY = currentY - state.getOriginalY1();
                int newHeight = originalHeight - deltaY;
                state.setScaleAnchorX(state.getOriginalX1());
                state.setScaleAnchorY(state.getOriginalY2());
                yield (double) newHeight / originalHeight;
            }
            case EDGE_BOTTOM -> {
                int deltaY = currentY - state.getOriginalY2();
                int newHeight = originalHeight + deltaY;
                state.setScaleAnchorX(state.getOriginalX1());
                state.setScaleAnchorY(state.getOriginalY1());
                yield (double) newHeight / originalHeight;
            }
            case EDGE_LEFT -> {
                int deltaX = currentX - state.getOriginalX1();
                int newWidth = originalWidth - deltaX;
                state.setScaleAnchorX(state.getOriginalX2());
                state.setScaleAnchorY(state.getOriginalY1());
                yield (double) newWidth / originalWidth;
            }
            case EDGE_RIGHT -> {
                int deltaX = currentX - state.getOriginalX2();
                int newWidth = originalWidth + deltaX;
                state.setScaleAnchorX(state.getOriginalX1());
                state.setScaleAnchorY(state.getOriginalY1());
                yield (double) newWidth / originalWidth;
            }
            default -> throw new IllegalArgumentException("Not an edge: " + activeHandle.getType());
        };
    }

    private void applyUniformEdgeScaling(TransformState state, double scaleFactor,
                                         TransformHandle activeHandle) {
        // Both axes scale by the same factor
        state.setScaleFactorX(scaleFactor);
        state.setScaleFactorY(scaleFactor);

        // Calculate new bounds with uniform scaling from anchor
        int anchorX = state.getScaleAnchorX();
        int anchorY = state.getScaleAnchorY();

        int newX1 = anchorX + (int) Math.round((state.getOriginalX1() - anchorX) * scaleFactor);
        int newY1 = anchorY + (int) Math.round((state.getOriginalY1() - anchorY) * scaleFactor);
        int newX2 = anchorX + (int) Math.round((state.getOriginalX2() - anchorX) * scaleFactor);
        int newY2 = anchorY + (int) Math.round((state.getOriginalY2() - anchorY) * scaleFactor);

        state.setPreviewX1(newX1);
        state.setPreviewY1(newY1);
        state.setPreviewX2(newX2);
        state.setPreviewY2(newY2);
    }

    private void applyNonUniformEdgeScaling(TransformState state, double scaleFactor,
                                            TransformHandle activeHandle) {
        // Non-uniform scaling - only move the dragged edge
        state.setScaleFactorX(1.0);
        state.setScaleFactorY(1.0);

        int anchorX = state.getScaleAnchorX();
        int anchorY = state.getScaleAnchorY();

        switch (activeHandle.getType()) {
            case EDGE_TOP -> {
                state.setScaleFactorY(scaleFactor);
                state.setPreviewY1(anchorY + (int) Math.round((state.getOriginalY1() - anchorY) * scaleFactor));
                state.setPreviewY2(state.getOriginalY2());
            }
            case EDGE_BOTTOM -> {
                state.setScaleFactorY(scaleFactor);
                state.setPreviewY1(state.getOriginalY1());
                state.setPreviewY2(anchorY + (int) Math.round((state.getOriginalY2() - anchorY) * scaleFactor));
            }
            case EDGE_LEFT -> {
                state.setScaleFactorX(scaleFactor);
                state.setPreviewX1(anchorX + (int) Math.round((state.getOriginalX1() - anchorX) * scaleFactor));
                state.setPreviewX2(state.getOriginalX2());
            }
            case EDGE_RIGHT -> {
                state.setScaleFactorX(scaleFactor);
                state.setPreviewX1(state.getOriginalX1());
                state.setPreviewX2(anchorX + (int) Math.round((state.getOriginalX2() - anchorX) * scaleFactor));
            }
        }
    }

    private void normalizePreviewBounds(TransformState state) {
        if (state.getPreviewX1() > state.getPreviewX2()) {
            int temp = state.getPreviewX1();
            state.setPreviewX1(state.getPreviewX2());
            state.setPreviewX2(temp);
        }
        if (state.getPreviewY1() > state.getPreviewY2()) {
            int temp = state.getPreviewY1();
            state.setPreviewY1(state.getPreviewY2());
            state.setPreviewY2(temp);
        }
    }

    private double snapToCardinalAngles(double angle) {
        double normalizedAngle = GeometryHelper.normalizeAngle(angle);

        double[] cardinalAngles = {0, 90, 180, 270, 360};
        double snapThreshold = 5.0;

        for (double cardinalAngle : cardinalAngles) {
            if (Math.abs(normalizedAngle - cardinalAngle) < snapThreshold) {
                return cardinalAngle == 360 ? 0 : cardinalAngle;
            }
        }

        return angle;
    }

    private List<TransformHandle> generateRotatedHandles(TransformState state, double[] rotatedCorners) {
        List<TransformHandle> rotatedHandles = new ArrayList<>();

        // Handle configuration
        double CORNER_HANDLE_RADIUS = 1.0;
        double EDGE_HANDLE_RADIUS = 1.0;
        double ROTATION_HANDLE_RADIUS = 1.0;
        double ROTATION_HANDLE_SCREEN_OFFSET = 40.0;

        // Extract rotated corners
        double x1 = rotatedCorners[0];
        double y1 = rotatedCorners[1];
        double x2 = rotatedCorners[2];
        double y2 = rotatedCorners[3];
        double x3 = rotatedCorners[4];
        double y3 = rotatedCorners[5];
        double x4 = rotatedCorners[6];
        double y4 = rotatedCorners[7];

        // Corner handles at rotated positions
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.CORNER_TOP_LEFT, x1, y1, CORNER_HANDLE_RADIUS));
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.CORNER_TOP_RIGHT, x2, y2, CORNER_HANDLE_RADIUS));
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.CORNER_BOTTOM_RIGHT, x3, y3, CORNER_HANDLE_RADIUS));
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.CORNER_BOTTOM_LEFT, x4, y4, CORNER_HANDLE_RADIUS));

        // Edge handles at rotated midpoints
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.EDGE_TOP, (x1 + x2) / 2.0, (y1 + y2) / 2.0, EDGE_HANDLE_RADIUS));
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.EDGE_RIGHT, (x2 + x3) / 2.0, (y2 + y3) / 2.0, EDGE_HANDLE_RADIUS));
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.EDGE_BOTTOM, (x3 + x4) / 2.0, (y3 + y4) / 2.0, EDGE_HANDLE_RADIUS));
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.EDGE_LEFT, (x4 + x1) / 2.0, (y4 + y1) / 2.0, EDGE_HANDLE_RADIUS));

        // Rotation handle - rotate the top center point
        double[] center = GeometryHelper.calculateCenter(
            state.getOriginalX1(), state.getOriginalY1(),
            state.getOriginalX2(), state.getOriginalY2());
        double centerX = center[0];
        double centerY = center[1];

        // Note: This assumes zoom of 1.0 - will need to be adjusted if zoom is needed
        double topCenterY = state.getOriginalY1() - ROTATION_HANDLE_SCREEN_OFFSET;
        double[] rotatedTopCenter = GeometryHelper.rotatePoint(
            centerX, topCenterY, centerX, centerY, state.getRotationAngleDegrees());
        rotatedHandles.add(new TransformHandle(TransformHandle.Type.ROTATION,
            rotatedTopCenter[0], rotatedTopCenter[1], ROTATION_HANDLE_RADIUS));

        return rotatedHandles;
    }
}
