package com.openmason.ui.viewport.ui;

import com.openmason.ui.viewport.Camera;
import com.openmason.ui.viewport.ViewportInputHandler;
import com.openmason.ui.viewport.state.ViewportState;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImString;

/**
 * Renders viewport controls window (grid, camera, status).
 * Follows Single Responsibility Principle - only handles viewport controls UI.
 */
public class ViewportControlsUI {

    private ViewportState viewportState;
    private Camera camera;
    private ViewportInputHandler inputHandler;
    private boolean modelRenderingEnabled = true;

    /**
     * Update UI state references.
     */
    public void updateState(ViewportState viewportState, Camera camera, ViewportInputHandler inputHandler, boolean modelRenderingEnabled) {
        this.viewportState = viewportState;
        this.camera = camera;
        this.inputHandler = inputHandler;
        this.modelRenderingEnabled = modelRenderingEnabled;
    }

    /**
     * Render viewport controls window.
     * Returns new grid visibility state if changed.
     */
    public ShowGridResult render() {
        boolean showGridChanged = false;
        boolean newShowGrid = viewportState.isShowGrid();

        ImGui.begin("Viewport Controls");

        // Grid controls
        if (ImGui.checkbox("Show Grid", newShowGrid)) {
            showGridChanged = true;
        }

        // Camera controls
        if (ImGui.button("Reset Camera")) {
            if (camera != null) {
                camera.reset();
            }
        }

        ImGui.separator();
        ImGui.text("Controls:");
        ImGui.text("Left Click & Drag in viewport: Rotate camera (Endless)");
        ImGui.text("Mouse Wheel in viewport: Zoom");

        ImGui.separator();
        ImGui.text("Status:");

        // Show viewport status
        if (viewportState != null) {
            ImGui.text("Viewport: " + viewportState.getWidth() + "x" + viewportState.getHeight());
            ImGui.text("Initialized: " + (viewportState.isInitialized() ? "Yes" : "No"));
        }

        // Model rendering controls
        boolean newModelRenderingEnabled = modelRenderingEnabled;
        if (ImGui.checkbox("Enable Model Rendering", newModelRenderingEnabled)) {
            modelRenderingEnabled = newModelRenderingEnabled;
        }

        // Show input handler status
        if (inputHandler != null) {
            renderInputHandlerStatus();
        } else {
            ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Input Handler: NULL");
        }

        // Show camera status
        if (camera != null) {
            renderCameraStatus();
        }

        ImGui.end();

        return new ShowGridResult(showGridChanged, newShowGrid, modelRenderingEnabled);
    }

    /**
     * Render input handler status section.
     */
    private void renderInputHandlerStatus() {
        ImGui.textColored(0.0f, 1.0f, 0.0f, 1.0f, "Input Handler: Active");

        // Show current dragging state
        if (inputHandler.isDragging()) {
            ImGui.textColored(0.0f, 1.0f, 0.0f, 1.0f, "Dragging camera - endless mode active!");
        }

        // Show mouse capture state
        if (inputHandler.isMouseCaptured()) {
            ImGui.textColored(1.0f, 1.0f, 0.0f, 1.0f, "Mouse captured - cursor hidden");
            ImGui.text("Cursor will return to: (" + (int)inputHandler.getSavedCursorX() + ", " + (int)inputHandler.getSavedCursorY() + ")");
        }

        // Show raw mouse motion status
        if (inputHandler.getWindowHandle() != 0L) {
            ImGui.text("Raw mouse motion: " + (inputHandler.isRawMouseMotionSupported() ? "Supported" : "Not supported"));
        } else {
            ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Warning: Window handle not set!");
            ImGui.text("Call setWindowHandle() to enable endless dragging");
        }
    }

    /**
     * Render camera status section.
     */
    private void renderCameraStatus() {
        ImGui.separator();
        ImGui.text("Camera:");
        ImGui.text("Mode: " + camera.getCameraMode());

        // Make coordinates copyable by using read-only input fields
        ImString distanceText = new ImString(String.format("%.1f", camera.getDistance()));
        ImString yawText = new ImString(String.format("%.1f°", camera.getYaw()));
        ImString pitchText = new ImString(String.format("%.1f°", camera.getPitch()));

        ImGui.text("Distance: ");
        ImGui.sameLine();
        ImGui.setNextItemWidth(80);
        ImGui.inputText("##distance", distanceText, ImGuiInputTextFlags.ReadOnly);

        ImGui.text("Yaw: ");
        ImGui.sameLine();
        ImGui.setNextItemWidth(80);
        ImGui.inputText("##yaw", yawText, ImGuiInputTextFlags.ReadOnly);

        ImGui.text("Pitch: ");
        ImGui.sameLine();
        ImGui.setNextItemWidth(80);
        ImGui.inputText("##pitch", pitchText, ImGuiInputTextFlags.ReadOnly);
    }

    /**
     * Result object for render() return value.
     */
    public static class ShowGridResult {
        public final boolean showGridChanged;
        public final boolean newShowGridValue;
        public final boolean modelRenderingEnabled;

        public ShowGridResult(boolean showGridChanged, boolean newShowGridValue, boolean modelRenderingEnabled) {
            this.showGridChanged = showGridChanged;
            this.newShowGridValue = newShowGridValue;
            this.modelRenderingEnabled = modelRenderingEnabled;
        }
    }
}
