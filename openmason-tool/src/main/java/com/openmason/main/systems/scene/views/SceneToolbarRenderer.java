package com.openmason.main.systems.scene.views;

import com.openmason.engine.rendering.viewer.gizmo.GizmoState;
import com.openmason.main.systems.scene.SceneViewerActions;
import com.openmason.main.systems.scene.SceneViewerUIState;
import imgui.ImGui;
import imgui.flag.ImGuiCol;

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

    private static String label(GizmoState.Mode mode) {
        return switch (mode) {
            case TRANSLATE -> "Move";
            case ROTATE -> "Rotate";
            case SCALE -> "Scale";
        };
    }

    private static String hint(GizmoState.Mode mode) {
        return switch (mode) {
            case TRANSLATE -> "Move (W)";
            case ROTATE -> "Rotate (E)";
            case SCALE -> "Scale (R)";
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
        tooltip("Duplicate selected (Ctrl+D)");
        ImGui.sameLine();
        if (ImGui.button("Delete")) {
            actions.deleteSelected();
        }
        tooltip("Delete selected (Delete)");
        ImGui.endDisabled();

        ImGui.sameLine();
        if (ImGui.button(hasSelection ? "Focus" : "Frame All")) {
            actions.focusSelected();
        }
        tooltip(hasSelection ? "Frame the selection (F)" : "Frame the whole scene (Home)");

        // Undo / redo, with the entry description so the user knows what steps back.
        ImGui.sameLine();
        ImGui.text("|");
        ImGui.sameLine();
        ImGui.beginDisabled(!actions.canUndo());
        if (ImGui.button("Undo")) {
            actions.undo();
        }
        ImGui.endDisabled();
        if (actions.canUndo()) {
            tooltip("Undo " + actions.undoDescription() + " (Ctrl+Z)");
        }
        ImGui.sameLine();
        ImGui.beginDisabled(!actions.canRedo());
        if (ImGui.button("Redo")) {
            actions.redo();
        }
        ImGui.endDisabled();
        if (actions.canRedo()) {
            tooltip("Redo " + actions.redoDescription() + " (Ctrl+Y)");
        }

        // Gizmo mode. Same three modes as the model editor's toolbar, driving the same
        // GizmoState — a selected instance is useless without a way to pick the handle.
        ImGui.sameLine();
        ImGui.text("|");
        var mode = actions.gizmoMode();
        for (var candidate : GizmoState.Mode.values()) {
            ImGui.sameLine();
            boolean active = mode == candidate;
            if (active) {
                ImGui.pushStyleColor(ImGuiCol.Button, ImGui.getColorU32(ImGuiCol.ButtonActive));
            }
            if (ImGui.button(label(candidate))) {
                actions.setGizmoMode(candidate);
            }
            if (active) {
                ImGui.popStyleColor();
            }
            tooltip(hint(candidate));
        }

        ImGui.sameLine();
        ImGui.text("|");
        ImGui.sameLine();
        ImGui.checkbox("Grid", state.getGridVisible());
        tooltip("Toggle grid (Ctrl+G)");
        ImGui.sameLine();
        ImGui.checkbox("Unrendered", state.getUnrendered());
        tooltip("Flat grey, untextured view");
        ImGui.sameLine();
        ImGui.checkbox("Snap", state.getGridSnappingEnabled());

        if (state.getGridSnappingEnabled().get()) {
            ImGui.sameLine();
            ImGui.setNextItemWidth(70);
            ImGui.dragFloat("##snapIncrement", state.getGridSnappingIncrement().getData(),
                    0.01f, 0.01f, 10.0f, "%.2f");
        }

        ImGui.sameLine();
        if (ImGui.button("Reset View")) {
            actions.resetView();
        }
    }

    private static void tooltip(String text) {
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(text);
        }
    }
}
