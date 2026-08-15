package com.openmason.main.systems.scene.views;

import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.main.systems.scene.ResolutionStatus;
import com.openmason.main.systems.scene.SceneDocument;
import com.openmason.main.systems.scene.SceneModelRef;
import com.openmason.main.systems.scene.SceneSelectionState;
import com.openmason.main.systems.scene.SceneViewerActions;
import imgui.ImGui;
import imgui.flag.ImGuiSelectableFlags;
import imgui.type.ImBoolean;

import java.util.List;

/**
 * Lists the scene's instances: select, toggle visibility, lock, rename, delete.
 */
public class SceneOutlinerImGui {

    public static final String WINDOW_TITLE = "Scene Outliner";

    private final SceneDocument document;
    private final SceneSelectionState selection;
    private final SceneViewerActions actions;
    private final ImBoolean visible;

    public SceneOutlinerImGui(SceneDocument document, SceneSelectionState selection,
                              SceneViewerActions actions, ImBoolean visible) {
        this.document = document;
        this.selection = selection;
        this.actions = actions;
        this.visible = visible;
    }

    public void render() {
        if (!visible.get()) {
            return;
        }
        if (ImGui.begin(WINDOW_TITLE, visible)) {
            renderImportBanner();
            renderInstanceList();
        }
        ImGui.end();
    }

    /**
     * Offered whenever a model resolved from its embedded copy — the state a scene is in
     * immediately after being opened in a different project.
     */
    private void renderImportBanner() {
        List<SceneModelRef> needingImport = document.modelsNeedingImport();
        if (needingImport.isEmpty()) {
            return;
        }
        ImGui.textWrapped(needingImport.size() + " model(s) are not in this project.");
        if (ImGui.button("Import to project")) {
            actions.importMissingModels();
        }
        ImGui.separator();
    }

    private void renderInstanceList() {
        List<ModelInstance> instances = document.instances();
        if (instances.isEmpty()) {
            ImGui.textDisabled("Scene is empty.");
            return;
        }

        for (ModelInstance instance : instances) {
            ImGui.pushID(instance.id());

            boolean shown = instance.isVisible();
            if (ImGui.smallButton(shown ? "O" : "-")) {
                instance.setVisible(!shown);
                actions.markDirty();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(shown ? "Visible" : "Hidden");
            }

            ImGui.sameLine();
            boolean locked = instance.isLocked();
            if (ImGui.smallButton(locked ? "L" : " ")) {
                instance.setLocked(!locked);
                actions.markDirty();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(locked ? "Locked" : "Unlocked");
            }

            ImGui.sameLine();
            String label = instance.name() + statusSuffix(instance);
            if (ImGui.selectable(label, selection.isSelected(instance.id()),
                    ImGuiSelectableFlags.AllowDoubleClick)) {
                if (ImGui.getIO().getKeyCtrl()) {
                    selection.toggle(instance.id());
                } else if (ImGui.getIO().getKeyShift()) {
                    selection.selectRangeTo(instance.id(), instances);
                } else {
                    selection.select(instance.id());
                }
                actions.syncGizmoToSelection();
                if (ImGui.isMouseDoubleClicked(0)) {
                    actions.focusSelected();
                }
            }

            if (ImGui.beginPopupContextItem()) {
                if (ImGui.menuItem("Focus")) {
                    selection.select(instance.id());
                    actions.focusSelected();
                }
                if (ImGui.menuItem("Duplicate")) {
                    selection.select(instance.id());
                    actions.duplicateSelected();
                }
                if (ImGui.menuItem("Edit Model...")) {
                    selection.select(instance.id());
                    actions.editSelectedModel();
                }
                ImGui.separator();
                if (ImGui.menuItem("Delete")) {
                    selection.select(instance.id());
                    actions.deleteSelected();
                }
                ImGui.endPopup();
            }

            ImGui.popID();
        }
    }

    /** Flags a model whose file drifted from, or is missing beside, the saved scene. */
    private String statusSuffix(ModelInstance instance) {
        SceneModelRef ref = document.modelFor(instance);
        if (ref == null) {
            return "  (missing)";
        }
        if (ref.status() == ResolutionStatus.REFERENCED_MODIFIED) {
            return "  *";
        }
        if (ref.status() == ResolutionStatus.EMBEDDED_FALLBACK) {
            return "  (embedded)";
        }
        if (ref.status() == ResolutionStatus.MISSING) {
            return "  (missing)";
        }
        return "";
    }
}
