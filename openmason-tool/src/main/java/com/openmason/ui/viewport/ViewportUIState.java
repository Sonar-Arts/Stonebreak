package com.openmason.ui.viewport;

import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;

/**
 * Holds all viewport UI state in a centralized, immutable-friendly manner.
 * Follows Single Responsibility Principle - only manages state.
 */
public class ViewportUIState {

    // Viewport visibility state
    private final ImBoolean gridVisible = new ImBoolean(true);
    private final ImBoolean axesVisible = new ImBoolean(true);
    private final ImBoolean gridSnappingEnabled = new ImBoolean(false);
    private final ImBoolean wireframeMode = new ImBoolean(false);
    private final ImBoolean showVertices = new ImBoolean(false);

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

    // Initialization state
    private boolean viewportInitialized = false;

    // Getters for visibility state
    public ImBoolean getGridVisible() { return gridVisible; }
    public ImBoolean getAxesVisible() { return axesVisible; }
    public ImBoolean getGridSnappingEnabled() { return gridSnappingEnabled; }
    public ImBoolean getWireframeMode() { return wireframeMode; }
    public ImBoolean getShowVertices() { return showVertices; }

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

    // Initialization state
    public boolean isViewportInitialized() { return viewportInitialized; }
    public void setViewportInitialized(boolean initialized) { this.viewportInitialized = initialized; }

    public void resetToDefaults() {
        currentViewModeIndex.set(0);
        currentCameraModeIndex.set(0);
        currentRenderModeIndex.set(0);
        cameraDistance.set(5.0f);
        cameraPitch.set(30.0f);
        cameraYaw.set(45.0f);
        cameraFOV.set(60.0f);
        gridVisible.set(true);
        axesVisible.set(true);
        gridSnappingEnabled.set(false);
        wireframeMode.set(false);
        showVertices.set(false);
    }

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
}
