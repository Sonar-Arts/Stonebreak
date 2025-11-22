package com.openmason.ui.viewport.views;

import com.openmason.ui.ViewportController;
import com.openmason.ui.viewport.ViewportActions;
import com.openmason.ui.viewport.ViewportState;
import com.openmason.ui.viewport.gizmo.GizmoState;
import imgui.ImGui;
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the transformation controls window.
 * Follows Single Responsibility Principle - only renders transform UI.
 */
public class TransformControlsView {

    private static final Logger logger = LoggerFactory.getLogger(TransformControlsView.class);

    private final ViewportState state;
    private final ViewportActions actions;
    private final ViewportController viewport;

    public TransformControlsView(ViewportState state, ViewportActions actions, ViewportController viewport) {
        this.state = state;
        this.actions = actions;
        this.viewport = viewport;
    }

    /**
     * Render transformation controls window.
     */
    public void render() {
        if (ImGui.begin("Transform Controls", state.getShowTransformationControls())) {

            // Gizmo visibility toggle
            boolean gizmoEnabled = viewport.isGizmoEnabled();
            if (ImGui.checkbox("Show Transform Gizmo (Ctrl+T)", new ImBoolean(gizmoEnabled))) {
                viewport.setGizmoEnabled(!gizmoEnabled);
                logger.info("Transform gizmo toggled: {}", !gizmoEnabled);
            }

            ImGui.separator();

            ImGui.text("Transform Mode:");
            ImGui.separator();

            // Get current gizmo mode
            GizmoState.Mode currentMode = viewport.getGizmoMode();

            // Translate mode radio button
            if (ImGui.radioButton("Translate Mode", currentMode == GizmoState.Mode.TRANSLATE)) {
                actions.setGizmoMode(GizmoState.Mode.TRANSLATE);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Move the model using arrow gizmos (G key)");
            }

            ImGui.sameLine();

            // Rotate mode radio button
            if (ImGui.radioButton("Rotate Mode", currentMode == GizmoState.Mode.ROTATE)) {
                actions.setGizmoMode(GizmoState.Mode.ROTATE);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Rotate the model using circular grabbers (R key)");
            }

            ImGui.sameLine();

            // Scale mode radio button
            if (ImGui.radioButton("Scale Mode", currentMode == GizmoState.Mode.SCALE)) {
                actions.setGizmoMode(GizmoState.Mode.SCALE);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Scale the model using box handles (S key)");
            }

            ImGui.separator();

            // Display mode-specific controls and information
            if (currentMode == GizmoState.Mode.SCALE) {
                renderScaleModeControls();
            } else if (currentMode == GizmoState.Mode.TRANSLATE) {
                ImGui.textWrapped("Drag the colored arrows to move along X (red), Y (green), or Z (blue) axes.");
            } else if (currentMode == GizmoState.Mode.ROTATE) {
                ImGui.textWrapped("Drag the circular grabbers to rotate around X (red), Y (green), or Z (blue) axes.");
            }

        }
        ImGui.end();
    }

    private void renderScaleModeControls() {
        // Uniform scaling toggle (only for Scale mode)
        boolean uniformScaling = viewport.getGizmoUniformScaling();
        if (ImGui.checkbox("Uniform Scaling", uniformScaling)) {
            actions.toggleUniformScaling();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("When enabled, all axes scale together. When disabled, each axis scales independently.");
        }

        ImGui.separator();

        // Help text for scale mode
        if (uniformScaling) {
            ImGui.textWrapped("Uniform scaling ON: Drag any handle to scale all axes together.");
        } else {
            ImGui.textWrapped("Uniform scaling OFF: Drag colored box handles to scale individual axes - X (red), Y (green), or Z (blue). Drag the center white box to scale uniformly.");
        }
    }
}
