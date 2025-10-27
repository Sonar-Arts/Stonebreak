package com.openmason.ui.components.textureCreator.tools.movetool.state;

import com.openmason.ui.components.textureCreator.selection.FreeSelection;
import com.openmason.ui.components.textureCreator.transform.TransformHandle;

import java.util.List;

/**
 * Holds all state for active transform operations.
 * Mutable state holder updated during drag operations.
 * Follows KISS principle - simple data container.
 *
 * @author Open Mason Team
 */
public class TransformState {

    // Drag tracking
    private int dragStartX;
    private int dragStartY;

    // Original bounds (before transform)
    private int originalX1;
    private int originalY1;
    private int originalX2;
    private int originalY2;

    // Preview bounds (during transform)
    private int previewX1;
    private int previewY1;
    private int previewX2;
    private int previewY2;

    // Rotation state
    private double rotationAngleDegrees;
    private double[] rotatedCorners; // [x1,y1, x2,y1, x2,y2, x1,y2] - 4 corners (8 values)
    private List<TransformHandle> rotatedHandles; // Handles in rotated positions
    private FreeSelection rotatedFreeSelectionPreview; // Preview of rotated free selection

    // Scale tracking (for maintaining free selection shapes)
    private int scaleAnchorX;
    private int scaleAnchorY;
    private double scaleFactorX;
    private double scaleFactorY;

    /**
     * Initializes transform state from selection bounds.
     *
     * @param x1 Left bound
     * @param y1 Top bound
     * @param x2 Right bound
     * @param y2 Bottom bound
     * @param dragX Initial drag X position
     * @param dragY Initial drag Y position
     */
    public void initialize(int x1, int y1, int x2, int y2, int dragX, int dragY) {
        this.originalX1 = x1;
        this.originalY1 = y1;
        this.originalX2 = x2;
        this.originalY2 = y2;

        this.previewX1 = x1;
        this.previewY1 = y1;
        this.previewX2 = x2;
        this.previewY2 = y2;

        this.dragStartX = dragX;
        this.dragStartY = dragY;

        this.rotationAngleDegrees = 0.0;
        this.rotatedCorners = null;
        this.rotatedHandles = null;
        this.rotatedFreeSelectionPreview = null;
    }

    /**
     * Clears rotated geometry state.
     */
    public void clearRotatedGeometry() {
        rotatedCorners = null;
        rotatedHandles = null;
        rotatedFreeSelectionPreview = null;
    }

    // ==================== GETTERS AND SETTERS ====================

    public int getDragStartX() {
        return dragStartX;
    }

    public int getDragStartY() {
        return dragStartY;
    }

    public int getOriginalX1() {
        return originalX1;
    }

    public int getOriginalY1() {
        return originalY1;
    }

    public int getOriginalX2() {
        return originalX2;
    }

    public int getOriginalY2() {
        return originalY2;
    }

    public int getPreviewX1() {
        return previewX1;
    }

    public void setPreviewX1(int previewX1) {
        this.previewX1 = previewX1;
    }

    public int getPreviewY1() {
        return previewY1;
    }

    public void setPreviewY1(int previewY1) {
        this.previewY1 = previewY1;
    }

    public int getPreviewX2() {
        return previewX2;
    }

    public void setPreviewX2(int previewX2) {
        this.previewX2 = previewX2;
    }

    public int getPreviewY2() {
        return previewY2;
    }

    public void setPreviewY2(int previewY2) {
        this.previewY2 = previewY2;
    }

    public double getRotationAngleDegrees() {
        return rotationAngleDegrees;
    }

    public void setRotationAngleDegrees(double rotationAngleDegrees) {
        this.rotationAngleDegrees = rotationAngleDegrees;
    }

    public double[] getRotatedCorners() {
        return rotatedCorners;
    }

    public void setRotatedCorners(double[] rotatedCorners) {
        this.rotatedCorners = rotatedCorners;
    }

    public List<TransformHandle> getRotatedHandles() {
        return rotatedHandles;
    }

    public void setRotatedHandles(List<TransformHandle> rotatedHandles) {
        this.rotatedHandles = rotatedHandles;
    }

    public FreeSelection getRotatedFreeSelectionPreview() {
        return rotatedFreeSelectionPreview;
    }

    public void setRotatedFreeSelectionPreview(FreeSelection rotatedFreeSelectionPreview) {
        this.rotatedFreeSelectionPreview = rotatedFreeSelectionPreview;
    }

    public int getScaleAnchorX() {
        return scaleAnchorX;
    }

    public void setScaleAnchorX(int scaleAnchorX) {
        this.scaleAnchorX = scaleAnchorX;
    }

    public int getScaleAnchorY() {
        return scaleAnchorY;
    }

    public void setScaleAnchorY(int scaleAnchorY) {
        this.scaleAnchorY = scaleAnchorY;
    }

    public double getScaleFactorX() {
        return scaleFactorX;
    }

    public void setScaleFactorX(double scaleFactorX) {
        this.scaleFactorX = scaleFactorX;
    }

    public double getScaleFactorY() {
        return scaleFactorY;
    }

    public void setScaleFactorY(double scaleFactorY) {
        this.scaleFactorY = scaleFactorY;
    }
}
