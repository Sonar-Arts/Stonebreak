package com.openmason.main.systems.viewport;

import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;

/**
 * Centralized viewport state management.
 * Consolidates all viewport-related state from multiple legacy state classes.
 * Follows Single Responsibility Principle - only manages viewport state.
 */
public class ViewportUIState {

    // Viewport dimensions
    private int width = 800;
    private int height = 600;

    // Viewport visibility state
    private final ImBoolean gridVisible = new ImBoolean(true);
    private final ImBoolean axesVisible = new ImBoolean(true);
    private final ImBoolean gridSnappingEnabled = new ImBoolean(false);
    private final ImBoolean wireframeMode = new ImBoolean(false);
    private final ImBoolean showVertices = new ImBoolean(false);
    private final ImBoolean showGizmo = new ImBoolean(true);

    // Window visibility state
    private final ImBoolean showCameraControls = new ImBoolean(false);
    private final ImBoolean showRenderingOptions = new ImBoolean(false);
    private final ImBoolean showTransformationControls = new ImBoolean(false);

    // View mode state
    private final String[] viewModes = {"Perspective", "Orthographic", "Front", "Side", "Top", "Bottom", "Isometric"};
    private final ImInt currentViewModeIndex = new ImInt(0);

    // Camera mode state
    private final String[] cameraModes = {"Arcball", "First-Person"};
    private final ImInt currentCameraModeIndex = new ImInt(0);

    // Render mode state
    private final String[] renderModes = {"Solid", "Wireframe", "Points", "Textured"};
    private final ImInt currentRenderModeIndex = new ImInt(0);

    // Camera state
    private final ImFloat cameraDistance = new ImFloat(5.0f);
    private final ImFloat cameraPitch = new ImFloat(30.0f);
    private final ImFloat cameraYaw = new ImFloat(45.0f);
    private final ImFloat cameraFOV = new ImFloat(60.0f);

    // Grid snapping state
    private final ImFloat gridSnappingIncrement = new ImFloat(0.5f); // Half block (0.5 units)

    // Initialization state
    private boolean viewportInitialized = false;

    // Focus tracking for input isolation (e.g., Tab key should only cycle edit modes when viewport is focused)
    private boolean viewportFocused = false;

    // Resize threshold to prevent excessive resizing from small ImGui layout fluctuations
    private static final int RESIZE_THRESHOLD = 5;

    // Getters for viewport dimensions
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public void setWidth(int width) { this.width = width; }
    public void setHeight(int height) { this.height = height; }
    public void setDimensions(int width, int height) {
        this.width = width;
        this.height = height;
    }

    // Getters for visibility state
    public ImBoolean getGridVisible() { return gridVisible; }
    public ImBoolean getAxesVisible() { return axesVisible; }
    public ImBoolean getGridSnappingEnabled() { return gridSnappingEnabled; }
    public ImBoolean getWireframeMode() { return wireframeMode; }
    public ImBoolean getShowVertices() { return showVertices; }
    public ImBoolean getShowGizmo() { return showGizmo; }

    // Getters for window visibility
    public ImBoolean getShowCameraControls() { return showCameraControls; }
    public ImBoolean getShowRenderingOptions() { return showRenderingOptions; }
    public ImBoolean getShowTransformationControls() { return showTransformationControls; }

    // Getters for view mode
    public String[] getViewModes() { return viewModes; }
    public ImInt getCurrentViewModeIndex() { return currentViewModeIndex; }
    public String getCurrentViewMode() { return viewModes[currentViewModeIndex.get()]; }

    // Getters for camera mode
    public String[] getCameraModes() { return cameraModes; }
    public ImInt getCurrentCameraModeIndex() { return currentCameraModeIndex; }
    public String getCurrentCameraMode() { return cameraModes[currentCameraModeIndex.get()]; }

    // Getters for render mode
    public String[] getRenderModes() { return renderModes; }
    public ImInt getCurrentRenderModeIndex() { return currentRenderModeIndex; }
    public String getCurrentRenderMode() { return renderModes[currentRenderModeIndex.get()]; }

    // Getters for camera state
    public ImFloat getCameraDistance() { return cameraDistance; }
    public ImFloat getCameraPitch() { return cameraPitch; }
    public ImFloat getCameraYaw() { return cameraYaw; }
    public ImFloat getCameraFOV() { return cameraFOV; }

    // Getters for grid snapping
    public ImFloat getGridSnappingIncrement() { return gridSnappingIncrement; }

    // Initialization state
    public void setViewportInitialized(boolean initialized) { this.viewportInitialized = initialized; }

    /**
     * Reset camera state to defaults.
     */
    public void resetCameraState() {
        cameraDistance.set(5.0f);
        cameraPitch.set(30.0f);
        cameraYaw.set(45.0f);
        cameraFOV.set(60.0f);
    }

    /**
     * Update camera state from viewport camera.
     */
    public void updateCameraState(float distance, float pitch, float yaw, float fov) {
        cameraDistance.set(distance);
        cameraPitch.set(pitch);
        cameraYaw.set(yaw);
        cameraFOV.set(fov);
    }

    /**
     * Check if dimensions have changed significantly.
     * Uses a threshold to prevent constant resizing from small pixel fluctuations.
     */
    public boolean dimensionsChanged(int newWidth, int newHeight) {
        int widthDiff = Math.abs(newWidth - width);
        int heightDiff = Math.abs(newHeight - height);
        return widthDiff >= RESIZE_THRESHOLD || heightDiff >= RESIZE_THRESHOLD;
    }

    /**
     * Get aspect ratio.
     */
    public float getAspectRatio() {
        return height > 0 ? (float) width / height : 1.0f;
    }

    /**
     * Toggle methods for convenience.
     */
    public void toggleGrid() {
        gridVisible.set(!gridVisible.get());
    }

    public void toggleAxes() {
        axesVisible.set(!axesVisible.get());
    }

    public void toggleWireframe() {
        wireframeMode.set(!wireframeMode.get());
    }

    public void toggleGizmo() {
        showGizmo.set(!showGizmo.get());
    }

    public void toggleGridSnapping() {
        gridSnappingEnabled.set(!gridSnappingEnabled.get());
    }

    public boolean isInitialized() {
        return viewportInitialized;
    }

    // Focus state accessors
    public boolean isViewportFocused() { return viewportFocused; }
    public void setViewportFocused(boolean focused) { this.viewportFocused = focused; }

    @Override
    public String toString() {
        return String.format("ViewportUIState{%dx%d, grid=%s, axes=%s, wireframe=%s, gizmo=%s, vertices=%s, initialized=%s, snapping=%s (%.4f)}",
                width, height, gridVisible.get(), axesVisible.get(), wireframeMode.get(), showGizmo.get(),
                showVertices.get(), viewportInitialized, gridSnappingEnabled.get(), gridSnappingIncrement.get());
    }
}
