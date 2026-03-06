package com.openmason.main.systems.menus.textureCreator.canvas;

/**
 * State management for the canvas display and interaction.
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
     * Calculate the zoom level that makes a canvas region fill the viewport.
     *
     * <p>Fits the region within the viewport with a small margin, choosing
     * the axis that constrains first so the entire region is visible.
     *
     * @param canvasWidth   region width in canvas pixels
     * @param canvasHeight  region height in canvas pixels
     * @param viewportWidth available viewport width in screen pixels
     * @param viewportHeight available viewport height in screen pixels
     * @return zoom level that fits the region
     */
    public static float calculateZoomToFit(int canvasWidth, int canvasHeight,
                                            float viewportWidth, float viewportHeight) {
        if (canvasWidth <= 0 || canvasHeight <= 0
                || viewportWidth <= 0 || viewportHeight <= 0) {
            return DEFAULT_ZOOM;
        }

        // 90% of viewport to leave a comfortable margin
        float margin = 0.9f;
        float zoomX = (viewportWidth * margin) / canvasWidth;
        float zoomY = (viewportHeight * margin) / canvasHeight;

        // Use the smaller zoom so the entire region fits
        float zoom = Math.min(zoomX, zoomY);
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    /**
     * Reset view to fit a canvas region within the viewport.
     *
     * <p>Sets zoom so the region fills the viewport and centers the pan offset.
     *
     * @param canvasWidth    region width in canvas pixels
     * @param canvasHeight   region height in canvas pixels
     * @param viewportWidth  available viewport width in screen pixels
     * @param viewportHeight available viewport height in screen pixels
     */
    public void resetViewToFit(int canvasWidth, int canvasHeight,
                                float viewportWidth, float viewportHeight) {
        setZoomLevel(calculateZoomToFit(canvasWidth, canvasHeight,
                                         viewportWidth, viewportHeight));
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
}
