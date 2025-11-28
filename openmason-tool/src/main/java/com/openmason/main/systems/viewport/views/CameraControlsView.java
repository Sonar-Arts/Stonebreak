package com.openmason.main.systems.viewport.views;

import com.openmason.main.systems.viewport.ViewportActions;
import com.openmason.main.systems.viewport.ViewportUIState;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the camera controls window.
 * Follows Single Responsibility Principle - only renders camera UI.
 */
public class CameraControlsView {

    private static final Logger logger = LoggerFactory.getLogger(CameraControlsView.class);

    private final ViewportUIState state;
    private final ViewportActions actions;

    public CameraControlsView(ViewportUIState state, ViewportActions actions) {
        this.state = state;
        this.actions = actions;
    }

    /**
     * Render camera controls window.
     */
    public void render() {
        if (ImGui.begin("Camera Controls", state.getShowCameraControls())) {

            ImGui.text("Camera Position:");

            if (ImGui.sliderFloat("Distance", state.getCameraDistance().getData(), 1.0f, 20.0f)) {
                actions.updateCameraDistance();
            }

            if (ImGui.sliderFloat("Pitch", state.getCameraPitch().getData(), -89.0f, 89.0f)) {
                actions.updateCameraPitch();
            }

            if (ImGui.sliderFloat("Yaw", state.getCameraYaw().getData(), -180.0f, 180.0f)) {
                actions.updateCameraYaw();
            }

            ImGui.separator();

            ImGui.text("Camera Settings:");

            if (ImGui.sliderFloat("Field of View", state.getCameraFOV().getData(), 30.0f, 120.0f)) {
                actions.updateCameraFOV();
            }

            ImGui.separator();

            if (ImGui.button("Reset Camera")) {
                actions.resetCamera();
            }

        }
        ImGui.end();
    }
}
