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
        // Get anchor point (opposite corner) and store for command creation
        int[] anchor = getAnchorForCorner(activeHandle.getType(), state);
        state.setScaleAnchorX(anchor[0]);
        state.setScaleAnchorY(anchor[1]);

        // Get original dragged corner position
        int[] origCorner = getCornerPosition(activeHandle.getType(),
            state.getOriginalX1(), state.getOriginalY1(),
            state.getOriginalX2(), state.getOriginalY2());

        // Calculate scale factors (preserve sign for flipping)
        double originalWidth = origCorner[0] - anchor[0];
        double originalHeight = origCorner[1] - anchor[1];
        double newWidth = currentX - anchor[0];
        double newHeight = currentY - anchor[1];

        // Prevent division by zero
        if (Math.abs(originalWidth) < 1) originalWidth = originalWidth < 0 ? -1 : 1;
        if (Math.abs(originalHeight) < 1) originalHeight = originalHeight < 0 ? -1 : 1;

        double scaleX = newWidth / originalWidth;
        double scaleY = newHeight / originalHeight;

        // Proportional scaling - use larger scale factor and preserve signs
        if (proportional) {
            double uniformScale = Math.max(Math.abs(scaleX), Math.abs(scaleY));
            scaleX = uniformScale * Math.signum(scaleX);
            scaleY = uniformScale * Math.signum(scaleY);
        }

        // Apply scaling and normalize bounds
        applyScaling(state, anchor[0], anchor[1], scaleX, scaleY);
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
        int originalWidth = state.getOriginalX2() - state.getOriginalX1();
        int originalHeight = state.getOriginalY2() - state.getOriginalY1();

        // Calculate scale factor and anchor for the dragged edge
        double scaleFactor;
        int anchorX, anchorY;
        double scaleX = 1.0, scaleY = 1.0;

        switch (activeHandle.getType()) {
            case EDGE_TOP -> {
                int newHeight = originalHeight - (currentY - state.getOriginalY1());
                scaleFactor = (double) newHeight / originalHeight;
                anchorX = state.getOriginalX1();
                anchorY = state.getOriginalY2();
                scaleY = scaleFactor;
            }
            case EDGE_BOTTOM -> {
                int newHeight = originalHeight + (currentY - state.getOriginalY2());
                scaleFactor = (double) newHeight / originalHeight;
                anchorX = state.getOriginalX1();
                anchorY = state.getOriginalY1();
                scaleY = scaleFactor;
            }
            case EDGE_LEFT -> {
                int newWidth = originalWidth - (currentX - state.getOriginalX1());
                scaleFactor = (double) newWidth / originalWidth;
                anchorX = state.getOriginalX2();
                anchorY = state.getOriginalY1();
                scaleX = scaleFactor;
            }
            case EDGE_RIGHT -> {
                int newWidth = originalWidth + (currentX - state.getOriginalX2());
                scaleFactor = (double) newWidth / originalWidth;
                anchorX = state.getOriginalX1();
                anchorY = state.getOriginalY1();
                scaleX = scaleFactor;
            }
            default -> throw new IllegalArgumentException("Not an edge: " + activeHandle.getType());
        }

        // Uniform scaling applies the edge's scale factor to both axes
        if (uniform) {
            scaleX = scaleFactor;
            scaleY = scaleFactor;
        }

        // Store anchor and apply scaling
        state.setScaleAnchorX(anchorX);
        state.setScaleAnchorY(anchorY);
        applyScaling(state, anchorX, anchorY, scaleX, scaleY);
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

    /**
     * Applies scaling transformation from an anchor point and normalizes bounds.
     * Common logic used by both corner scaling and edge stretching.
     */
    private void applyScaling(TransformState state, int anchorX, int anchorY, double scaleX, double scaleY) {
        // Store scale factors for command creation
        state.setScaleFactorX(scaleX);
        state.setScaleFactorY(scaleY);

        // Calculate new bounds from anchor
        int newX1 = anchorX + (int) Math.round((state.getOriginalX1() - anchorX) * scaleX);
        int newY1 = anchorY + (int) Math.round((state.getOriginalY1() - anchorY) * scaleY);
        int newX2 = anchorX + (int) Math.round((state.getOriginalX2() - anchorX) * scaleX);
        int newY2 = anchorY + (int) Math.round((state.getOriginalY2() - anchorY) * scaleY);

        // Normalize bounds (ensure x1 < x2, y1 < y2)
        state.setPreviewX1(Math.min(newX1, newX2));
        state.setPreviewY1(Math.min(newY1, newY2));
        state.setPreviewX2(Math.max(newX1, newX2));
        state.setPreviewY2(Math.max(newY1, newY2));
    }

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

    private double snapToCardinalAngles(double angle) {
        double normalizedAngle = GeometryHelper.normalizeAngle(angle);
        double snapThreshold = 5.0;

        // Snap to 0°, 90°, 180°, 270° if within threshold
        double[] cardinalAngles = {0, 90, 180, 270};
        for (double cardinalAngle : cardinalAngles) {
            if (Math.abs(normalizedAngle - cardinalAngle) < snapThreshold) {
                return cardinalAngle;
            }
        }

        // Check 360° wraps to 0°
        if (Math.abs(normalizedAngle - 360) < snapThreshold) {
            return 0;
        }

        return angle;
    }

    private List<TransformHandle> generateRotatedHandles(TransformState state, double[] corners) {
        // Handle radii match HandleManager configuration
        final double HANDLE_RADIUS = 1.0;
        final double ROTATION_OFFSET = 40.0;

        List<TransformHandle> handles = new ArrayList<>();

        // Corner handles (4 corners)
        handles.add(new TransformHandle(TransformHandle.Type.CORNER_TOP_LEFT, corners[0], corners[1], HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.CORNER_TOP_RIGHT, corners[2], corners[3], HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.CORNER_BOTTOM_RIGHT, corners[4], corners[5], HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.CORNER_BOTTOM_LEFT, corners[6], corners[7], HANDLE_RADIUS));

        // Edge handles (midpoints between corners)
        handles.add(new TransformHandle(TransformHandle.Type.EDGE_TOP,
            (corners[0] + corners[2]) / 2.0, (corners[1] + corners[3]) / 2.0, HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.EDGE_RIGHT,
            (corners[2] + corners[4]) / 2.0, (corners[3] + corners[5]) / 2.0, HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.EDGE_BOTTOM,
            (corners[4] + corners[6]) / 2.0, (corners[5] + corners[7]) / 2.0, HANDLE_RADIUS));
        handles.add(new TransformHandle(TransformHandle.Type.EDGE_LEFT,
            (corners[6] + corners[0]) / 2.0, (corners[7] + corners[1]) / 2.0, HANDLE_RADIUS));

        // Rotation handle - positioned above center, rotated with selection
        double[] center = GeometryHelper.calculateCenter(
            state.getOriginalX1(), state.getOriginalY1(),
            state.getOriginalX2(), state.getOriginalY2());
        double[] rotatedPosition = GeometryHelper.rotatePoint(
            center[0], state.getOriginalY1() - ROTATION_OFFSET,
            center[0], center[1], state.getRotationAngleDegrees());
        handles.add(new TransformHandle(TransformHandle.Type.ROTATION,
            rotatedPosition[0], rotatedPosition[1], HANDLE_RADIUS));

        return handles;
    }
}
