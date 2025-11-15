package com.openmason.ui.viewport.state;

import com.openmason.ui.preferences.PreferencesManager;
import com.openmason.ui.viewport.util.SnappingUtil;

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
    private final boolean gridSnappingEnabled;
    private final float gridSnappingIncrement;

    /**
     * Private constructor for builder pattern.
     */
    private ViewportState(int width, int height, boolean showGrid, boolean showAxes,
                         boolean wireframeMode, boolean initialized,
                         boolean gridSnappingEnabled, float gridSnappingIncrement) {
        this.width = width;
        this.height = height;
        this.showGrid = showGrid;
        this.showAxes = showAxes;
        this.wireframeMode = wireframeMode;
        this.initialized = initialized;
        this.gridSnappingEnabled = gridSnappingEnabled;
        this.gridSnappingIncrement = gridSnappingIncrement;
    }

    /**
     * Create default viewport state.
     * Loads grid snapping settings from preferences.
     */
    public static ViewportState createDefault() {
        PreferencesManager prefs = new PreferencesManager();
        return new Builder()
            .width(800)
            .height(600)
            .showGrid(true)
            .showAxes(false)
            .wireframeMode(false)
            .initialized(false)
            .gridSnappingEnabled(prefs.isGridSnappingEnabled())
            .gridSnappingIncrement(prefs.getGridSnappingIncrement())
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
            .initialized(initialized)
            .gridSnappingEnabled(gridSnappingEnabled)
            .gridSnappingIncrement(gridSnappingIncrement);
    }

    // Getters
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isShowGrid() { return showGrid; }
    public boolean isShowAxes() { return showAxes; }
    public boolean isWireframeMode() { return wireframeMode; }
    public boolean isInitialized() { return initialized; }
    public boolean isGridSnappingEnabled() { return gridSnappingEnabled; }
    public float getGridSnappingIncrement() { return gridSnappingIncrement; }

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
        return String.format("ViewportState{%dx%d, grid=%s, axes=%s, wireframe=%s, initialized=%s, snapping=%s (%.4f)}",
                           width, height, showGrid, showAxes, wireframeMode, initialized,
                           gridSnappingEnabled, gridSnappingIncrement);
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
        private boolean gridSnappingEnabled = false;
        // Default: Half block (0.5 units) = 2 snap positions per visual grid square
        // Provides good balance between precision and visual alignment with 1.0 unit grid
        private float gridSnappingIncrement = SnappingUtil.SNAP_HALF_BLOCK;

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

        public Builder gridSnappingEnabled(boolean gridSnappingEnabled) {
            this.gridSnappingEnabled = gridSnappingEnabled;
            return this;
        }

        public Builder gridSnappingIncrement(float gridSnappingIncrement) {
            this.gridSnappingIncrement = gridSnappingIncrement;
            return this;
        }

        public ViewportState build() {
            return new ViewportState(width, height, showGrid, showAxes, wireframeMode, initialized,
                                   gridSnappingEnabled, gridSnappingIncrement);
        }
    }
}
