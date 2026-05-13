package com.openmason.main.systems.menus.animationEditor;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.animationEditor.panels.FileBarPanel;
import com.openmason.main.systems.menus.animationEditor.panels.KeyframeInspectorPanel;
import com.openmason.main.systems.menus.animationEditor.panels.PartListPanel;
import com.openmason.main.systems.menus.animationEditor.panels.TimelinePanel;
import com.openmason.main.systems.menus.animationEditor.panels.TransportPanel;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

/**
 * Single-window Animation Editor — owns the controller, composes the panels,
 * routes shortcuts. All real UI logic lives in the {@code panels} package.
 */
public final class AnimationEditorImGui {

    private static final String WINDOW_TITLE = "Animation Editor";

    private final AnimationEditorController controller = new AnimationEditorController();
    private final ImBoolean visible = new ImBoolean(false);

    private final FileBarPanel fileBar = new FileBarPanel(controller);
    private final TransportPanel transport = new TransportPanel(controller);
    private final PartListPanel partList = new PartListPanel(controller);
    private final TimelinePanel timeline = new TimelinePanel(controller);
    private final KeyframeInspectorPanel inspector = new KeyframeInspectorPanel(controller);

    public AnimationEditorController getController() {
        return controller;
    }

    public void setFileDialogService(FileDialogService service) {
        fileBar.setFileDialogService(service);
    }

    public void show() { visible.set(true); }
    public void hide() { visible.set(false); controller.endSession(); }
    public boolean isVisible() { return visible.get(); }

    public void bindViewport(ModelPartManager partManager) {
        controller.bindViewport(partManager);
    }

    /** Per-frame entry point. Caller passes deltaTime so playback can advance. */
    public void render(float deltaTime) {
        if (!visible.get()) return;

        controller.tickPlayback(deltaTime);

        ImGui.setNextWindowSize(1100, 600, ImGuiCond.FirstUseEver);
        if (!ImGui.begin(WINDOW_TITLE, visible, ImGuiWindowFlags.NoCollapse)) {
            ImGui.end();
            if (!visible.get()) controller.endSession();
            return;
        }

        controller.beginSession();

        fileBar.render();
        ImGui.separator();
        transport.render();
        handleShortcuts();
        ImGui.separator();

        if (ImGui.beginTable("##animEditorLayout", 3,
                ImGuiTableFlags.Resizable | ImGuiTableFlags.BordersInnerV)) {
            ImGui.tableSetupColumn("Parts", ImGuiTableColumnFlags.WidthStretch, 0.18f);
            ImGui.tableSetupColumn("Timeline", ImGuiTableColumnFlags.WidthStretch, 0.55f);
            ImGui.tableSetupColumn("Inspector", ImGuiTableColumnFlags.WidthStretch, 0.27f);

            ImGui.tableNextRow();
            ImGui.tableNextColumn(); partList.render();
            ImGui.tableNextColumn(); timeline.render();
            ImGui.tableNextColumn(); inspector.render();

            ImGui.endTable();
        }

        ImGui.end();
        if (!visible.get()) controller.endSession();
    }

    private void handleShortcuts() {
        if (!ImGui.isWindowFocused() && !ImGui.isWindowHovered()) return;

        boolean ctrl = ImGui.getIO().getKeyCtrl();
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.Z)) {
            controller.undo();
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.Y)) {
            controller.redo();
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.S)) {
            if (controller.state().filePath() != null) controller.save();
            else fileBar.promptSaveAs();
        }
        if (ImGui.isKeyPressed(ImGuiKey.Space)) {
            controller.state().setPlaying(!controller.state().playing());
        }
        if (ImGui.isKeyPressed(ImGuiKey.K)) {
            String partId = controller.state().selectedPartId();
            if (partId != null) controller.insertKeyframeAtPlayhead(partId);
        }
        if (ImGui.isKeyPressed(ImGuiKey.Delete)) {
            String partId = controller.state().selectedPartId();
            int idx = controller.state().selectedKeyframeIndex();
            if (partId != null && idx >= 0) {
                controller.deleteKeyframe(partId, idx);
                controller.state().setSelectedKeyframeIndex(-1);
            }
        }
    }
}
