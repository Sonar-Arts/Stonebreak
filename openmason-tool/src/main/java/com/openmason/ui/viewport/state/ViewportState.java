package com.openmason.ui.viewport.state;

/**
 * Immutable state object for viewport configuration.
 * Holds viewport dimensions and rendering flags.
 */
public class ViewportState {

    private final int width;
    private final int height;
    private final boolean showGrid;
    private final boolean showAxes;
    private final boolean wireframeMode;
    private final boolean initialized;

    /**
     * Private constructor for builder pattern.
     */
    private ViewportState(int width, int height, boolean showGrid, boolean showAxes,
                         boolean wireframeMode, boolean initialized) {
        this.width = width;
        this.height = height;
        this.showGrid = showGrid;
        this.showAxes = showAxes;
        this.wireframeMode = wireframeMode;
        this.initialized = initialized;
    }

    /**
     * Create default viewport state.
     */
    public static ViewportState createDefault() {
        return new Builder()
            .width(800)
            .height(600)
            .showGrid(true)
            .showAxes(false)
            .wireframeMode(false)
            .initialized(false)
            .build();
    }

    /**
     * Create builder from existing state (for immutable updates).
     */
    public Builder toBuilder() {
        return new Builder()
            .width(width)
            .height(height)
            .showGrid(showGrid)
            .showAxes(showAxes)
            .wireframeMode(wireframeMode)
            .initialized(initialized);
    }

    // Getters
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isShowGrid() { return showGrid; }
    public boolean isShowAxes() { return showAxes; }
    public boolean isWireframeMode() { return wireframeMode; }
    public boolean isInitialized() { return initialized; }

    /**
     * Check if dimensions have changed significantly.
     * Uses a threshold to prevent constant resizing from small pixel fluctuations.
     */
    public boolean dimensionsChanged(int newWidth, int newHeight) {
        // Use a threshold of 5 pixels to prevent excessive resizing
        // from small ImGui layout fluctuations
        final int RESIZE_THRESHOLD = 5;

        int widthDiff = Math.abs(newWidth - width);
        int heightDiff = Math.abs(newHeight - height);

        return widthDiff >= RESIZE_THRESHOLD || heightDiff >= RESIZE_THRESHOLD;
    }

    /**
     * Check if dimensions are valid.
     */
    public boolean hasValidDimensions() {
        return width > 0 && height > 0;
    }

    /**
     * Get aspect ratio.
     */
    public float getAspectRatio() {
        return height > 0 ? (float) width / height : 1.0f;
    }

    @Override
    public String toString() {
        return String.format("ViewportState{%dx%d, grid=%s, axes=%s, wireframe=%s, initialized=%s}",
                           width, height, showGrid, showAxes, wireframeMode, initialized);
    }

    /**
     * Builder for creating ViewportState instances.
     */
    public static class Builder {
        private int width = 800;
        private int height = 600;
        private boolean showGrid = true;
        private boolean showAxes = false;
        private boolean wireframeMode = false;
        private boolean initialized = false;

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        public Builder height(int height) {
            this.height = height;
            return this;
        }

        public Builder dimensions(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder showGrid(boolean showGrid) {
            this.showGrid = showGrid;
            return this;
        }

        public Builder showAxes(boolean showAxes) {
            this.showAxes = showAxes;
            return this;
        }

        public Builder wireframeMode(boolean wireframeMode) {
            this.wireframeMode = wireframeMode;
            return this;
        }

        public Builder initialized(boolean initialized) {
            this.initialized = initialized;
            return this;
        }

        public ViewportState build() {
            return new ViewportState(width, height, showGrid, showAxes, wireframeMode, initialized);
        }
    }
}
