package com.openmason.ui.textureCreator.canvas;

/**
 * State management for the canvas display and interaction.
 *
 * Handles zoom, pan, and view transformation state.
 * Follows KISS principle - simple state with clear responsibilities.
 *
 * @author Open Mason Team
 */
public class CanvasState {

    // Zoom levels
    private static final float MIN_ZOOM = 0.25f;  // Allow zooming out to 1/4 size
    private static final float MAX_ZOOM = 32.0f;
    private static final float DEFAULT_ZOOM = 8.0f;

    // View state
    private float zoomLevel;
    private float panOffsetX;
    private float panOffsetY;
    private boolean isPanning;
    private float lastMouseX;
    private float lastMouseY;

    /**
     * Create new canvas state with default values.
     */
    public CanvasState() {
        this.zoomLevel = DEFAULT_ZOOM;
        this.panOffsetX = 0.0f;
        this.panOffsetY = 0.0f;
        this.isPanning = false;
        this.lastMouseX = 0.0f;
        this.lastMouseY = 0.0f;
    }

    /**
     * Get current zoom level.
     * @return zoom level (1.0 = actual size, higher = zoomed in)
     */
    public float getZoomLevel() {
        return zoomLevel;
    }

    /**
     * Set zoom level with clamping to valid range.
     * @param zoom desired zoom level
     */
    public void setZoomLevel(float zoom) {
        this.zoomLevel = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    /**
     * Zoom in by a factor.
     * @param factor zoom multiplier (e.g., 2.0 to double zoom)
     */
    public void zoomIn(float factor) {
        setZoomLevel(zoomLevel * factor);
    }

    /**
     * Zoom out by a factor.
     * @param factor zoom divisor (e.g., 2.0 to halve zoom)
     */
    public void zoomOut(float factor) {
        setZoomLevel(zoomLevel / factor);
    }

    /**
     * Reset zoom to default level.
     */
    public void resetZoom() {
        this.zoomLevel = DEFAULT_ZOOM;
    }

    /**
     * Get horizontal pan offset.
     * @return X offset in pixels
     */
    public float getPanOffsetX() {
        return panOffsetX;
    }

    /**
     * Get vertical pan offset.
     * @return Y offset in pixels
     */
    public float getPanOffsetY() {
        return panOffsetY;
    }

    /**
     * Set pan offset.
     * @param offsetX horizontal offset
     * @param offsetY vertical offset
     */
    public void setPanOffset(float offsetX, float offsetY) {
        this.panOffsetX = offsetX;
        this.panOffsetY = offsetY;
    }

    /**
     * Adjust pan offset by delta.
     * @param deltaX horizontal change
     * @param deltaY vertical change
     */
    public void adjustPanOffset(float deltaX, float deltaY) {
        this.panOffsetX += deltaX;
        this.panOffsetY += deltaY;
    }

    /**
     * Reset pan to center.
     */
    public void resetPan() {
        this.panOffsetX = 0.0f;
        this.panOffsetY = 0.0f;
    }

    /**
     * Check if currently panning.
     * @return true if pan drag in progress
     */
    public boolean isPanning() {
        return isPanning;
    }

    /**
     * Start panning operation.
     * @param mouseX current mouse X position
     * @param mouseY current mouse Y position
     */
    public void startPanning(float mouseX, float mouseY) {
        this.isPanning = true;
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
    }

    /**
     * Update panning with new mouse position.
     * @param mouseX current mouse X position
     * @param mouseY current mouse Y position
     */
    public void updatePanning(float mouseX, float mouseY) {
        if (isPanning) {
            float deltaX = mouseX - lastMouseX;
            float deltaY = mouseY - lastMouseY;
            adjustPanOffset(deltaX, deltaY);
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
        }
    }

    /**
     * Stop panning operation.
     */
    public void stopPanning() {
        this.isPanning = false;
    }

    /**
     * Reset all view transformations to defaults.
     */
    public void resetView() {
        resetZoom();
        resetPan();
    }

    /**
     * Convert screen coordinates to canvas pixel coordinates.
     *
     * @param screenX screen X coordinate
     * @param screenY screen Y coordinate
     * @param canvasDisplayX canvas display area X offset
     * @param canvasDisplayY canvas display area Y offset
     * @param result array to store result [x, y]
     * @return true if coordinates are within canvas bounds
     */
    public boolean screenToCanvasCoords(float screenX, float screenY,
                                        float canvasDisplayX, float canvasDisplayY,
                                        int[] result) {
        // Account for pan and zoom
        float canvasX = (screenX - canvasDisplayX - panOffsetX) / zoomLevel;
        float canvasY = (screenY - canvasDisplayY - panOffsetY) / zoomLevel;

        // Convert to integer pixel coordinates
        result[0] = (int) Math.floor(canvasX);
        result[1] = (int) Math.floor(canvasY);

        return true; // Bounds checking done by caller
    }

    /**
     * Convert canvas pixel coordinates to screen coordinates.
     *
     * @param canvasX canvas pixel X coordinate
     * @param canvasY canvas pixel Y coordinate
     * @param canvasDisplayX canvas display area X offset
     * @param canvasDisplayY canvas display area Y offset
     * @param result array to store result [x, y]
     */
    public void canvasToScreenCoords(int canvasX, int canvasY,
                                     float canvasDisplayX, float canvasDisplayY,
                                     float[] result) {
        result[0] = canvasX * zoomLevel + panOffsetX + canvasDisplayX;
        result[1] = canvasY * zoomLevel + panOffsetY + canvasDisplayY;
    }
}
