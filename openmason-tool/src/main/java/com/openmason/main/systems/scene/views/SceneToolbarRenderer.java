package com.openmason.main.systems.scene.views;

import com.openmason.main.systems.scene.SceneViewerActions;
import com.openmason.main.systems.scene.SceneViewerUIState;
import imgui.ImGui;

/**
 * Single-row toolbar above the scene viewport, mirroring the model editor's.
 */
public class SceneToolbarRenderer {

    private final SceneViewerUIState state;
    private final SceneViewerActions actions;

    public SceneToolbarRenderer(SceneViewerUIState state, SceneViewerActions actions) {
        this.state = state;
        this.actions = actions;
    }

    private static String label(com.openmason.engine.rendering.viewer.gizmo.GizmoState.Mode mode) {
        return switch (mode) {
            case TRANSLATE -> "Move";
            case ROTATE -> "Rotate";
            case SCALE -> "Scale";
        };
    }

    public void render() {
        boolean hasSelection = actions.hasSelection();

        if (ImGui.button("Add Model...")) {
            actions.requestAddModel();
        }

        ImGui.sameLine();
        ImGui.beginDisabled(!hasSelection);
        if (ImGui.button("Duplicate")) {
            actions.duplicateSelected();
        }
        ImGui.sameLine();
        if (ImGui.button("Delete")) {
            actions.deleteSelected();
        }
        ImGui.sameLine();
        if (ImGui.button("Focus")) {
            actions.focusSelected();
        }
        ImGui.endDisabled();

        // Gizmo mode. Same three modes as the model editor's toolbar, driving the same
        // GizmoState — a selected instance is useless without a way to pick the handle.
        ImGui.sameLine();
        ImGui.text("|");
        var mode = actions.gizmoMode();
        for (var candidate : com.openmason.engine.rendering.viewer.gizmo.GizmoState.Mode.values()) {
            ImGui.sameLine();
            boolean active = mode == candidate;
            if (active) {
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button,
                        ImGui.getColorU32(imgui.flag.ImGuiCol.ButtonActive));
            }
            if (ImGui.button(label(candidate))) {
                actions.setGizmoMode(candidate);
            }
            if (active) {
                ImGui.popStyleColor();
            }
        }

        ImGui.sameLine();
        ImGui.text("|");
        ImGui.sameLine();
        ImGui.checkbox("Grid", state.getGridVisible());
        ImGui.sameLine();
        ImGui.checkbox("Snap", state.getGridSnappingEnabled());

        if (state.getGridSnappingEnabled().get()) {
            ImGui.sameLine();
            ImGui.setNextItemWidth(70);
            ImGui.dragFloat("##snapIncrement", state.getGridSnappingIncrement().getData(),
                    0.01f, 0.01f, 10.0f, "%.2f");
        }
    }
}
