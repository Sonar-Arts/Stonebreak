package com.openmason.main.systems.scene.views;

import com.openmason.engine.format.omsc.OMSCFormat;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.main.systems.scene.ResolutionStatus;
import com.openmason.main.systems.scene.SceneDocument;
import com.openmason.main.systems.scene.SceneModelRef;
import com.openmason.main.systems.scene.SceneSelectionState;
import com.openmason.main.systems.scene.SceneViewerActions;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiSelectableFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.util.List;
import java.util.Locale;

/**
 * Lists the scene's instances: select, toggle visibility, lock, rename, delete.
 *
 * <p>Also lists placements whose model is missing (kept on paper so they survive a save)
 * and offers a filter box for large scenes.
 */
public class SceneOutlinerImGui {

    public static final String WINDOW_TITLE = "Scene Outliner";

    private final SceneDocument document;
    private final SceneSelectionState selection;
    private final SceneViewerActions actions;
    private final ImBoolean visible;

    private final ImString filter = new ImString(64);
    private final ImString renameBuffer = new ImString(128);

    /** Instance being renamed inline, or null. */
    private String renamingId;
    private boolean renameNeedsFocus;

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
            renderFilter();
            renderInstanceList();
            renderOrphans();
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

    private void renderFilter() {
        ImGui.setNextItemWidth(-1);
        ImGui.inputTextWithHint("##sceneFilter", "Filter instances...", filter);
    }

    private boolean matchesFilter(String name) {
        String needle = filter.get().trim().toLowerCase(Locale.ROOT);
        return needle.isEmpty() || (name != null && name.toLowerCase(Locale.ROOT).contains(needle));
    }

    private void renderInstanceList() {
        List<ModelInstance> instances = document.instances();
        if (instances.isEmpty()) {
            ImGui.textDisabled("Scene is empty.");
            return;
        }

        for (ModelInstance instance : instances) {
            if (!matchesFilter(instance.name())) {
                continue;
            }
            ImGui.pushID(instance.id());

            boolean shown = instance.isVisible();
            if (ImGui.smallButton(shown ? "O" : "-")) {
                actions.setVisible(instance, !shown);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(shown ? "Visible" : "Hidden");
            }

            ImGui.sameLine();
            boolean locked = instance.isLocked();
            if (ImGui.smallButton(locked ? "L" : " ")) {
                actions.setLocked(instance, !locked);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(locked ? "Locked" : "Unlocked");
            }

            ImGui.sameLine();
            if (instance.id().equals(renamingId)) {
                renderRenameField(instance);
            } else {
                renderRow(instance, instances);
            }

            ImGui.popID();
        }
    }

    private void renderRow(ModelInstance instance, List<ModelInstance> instances) {
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
                actions.select(instance.id());
                actions.focusSelected();
            }
            if (ImGui.menuItem("Rename")) {
                beginRename(instance);
            }
            if (ImGui.menuItem("Duplicate")) {
                actions.select(instance.id());
                actions.duplicateSelected();
            }
            if (ImGui.menuItem("Edit Model...")) {
                actions.select(instance.id());
                actions.editSelectedModel();
            }
            ImGui.separator();
            if (ImGui.menuItem("Delete")) {
                actions.select(instance.id());
                actions.deleteSelected();
            }
            ImGui.endPopup();
        }
    }

    private void beginRename(ModelInstance instance) {
        renamingId = instance.id();
        renameBuffer.set(instance.name());
        renameNeedsFocus = true;
    }

    private void renderRenameField(ModelInstance instance) {
        if (renameNeedsFocus) {
            ImGui.setKeyboardFocusHere();
            renameNeedsFocus = false;
        }
        ImGui.setNextItemWidth(-1);
        boolean committed = ImGui.inputText("##rename", renameBuffer,
                ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.AutoSelectAll);
        if (committed || ImGui.isItemDeactivatedAfterEdit()) {
            actions.rename(instance, renameBuffer.get());
            renamingId = null;
        } else if (ImGui.isItemDeactivated()) {
            renamingId = null; // cancelled (Escape / focus lost without an edit)
        }
    }

    /** Placements carried on paper because their model never loaded. */
    private void renderOrphans() {
        List<OMSCFormat.InstanceEntry> orphans = document.orphanInstances();
        if (orphans.isEmpty()) {
            return;
        }
        ImGui.separator();
        ImGui.textDisabled("Missing model (" + orphans.size() + "):");
        for (OMSCFormat.InstanceEntry orphan : orphans) {
            if (!matchesFilter(orphan.name())) {
                continue;
            }
            ImGui.pushID(orphan.id());
            ImGui.textDisabled("  " + orphan.name() + "  (missing)");
            if (ImGui.isItemHovered()) {
                SceneModelRef ref = document.modelBySessionId(orphan.modelId());
                String source = ref != null && ref.sourceName() != null ? ref.sourceName() : orphan.modelId();
                ImGui.setTooltip("Model '" + source + "' could not be loaded.\n"
                        + "This placement is kept in the file and comes back once the model is available.");
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
