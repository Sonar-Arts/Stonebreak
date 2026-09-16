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
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

/**
 * Single-window Animation Editor — owns the controller, composes the panels,
 * routes shortcuts, and guards unsaved changes (New / Open / closing the
 * window ask before discarding). All real UI logic lives in the
 * {@code panels} package.
 */
public final class AnimationEditorImGui {

    private static final String WINDOW_TITLE = "Animation Editor";
    private static final String DISCARD_POPUP = "Unsaved animation changes";

    private final AnimationEditorController controller = new AnimationEditorController();
    private final ImBoolean visible = new ImBoolean(false);

    private final FileBarPanel fileBar = new FileBarPanel(controller);
    private final TransportPanel transport = new TransportPanel(controller);
    private final PartListPanel partList = new PartListPanel(controller);
    private final TimelinePanel timeline = new TimelinePanel(controller);
    private final KeyframeInspectorPanel inspector = new KeyframeInspectorPanel(controller);

    /** Action waiting on the discard-confirmation popup; null when none. */
    private Runnable pendingDiscardAction;
    private boolean openDiscardPopup;

    public AnimationEditorImGui() {
        fileBar.setDiscardGuard(this::confirmDiscardThen);
    }

    public AnimationEditorController getController() {
        return controller;
    }

    public void setFileDialogService(FileDialogService service) {
        fileBar.setFileDialogService(service);
        inspector.setFileDialogService(service);
    }

    public void show() { visible.set(true); }
    public void hide() { visible.set(false); controller.endSession(); }
    public boolean isVisible() { return visible.get(); }

    /** Release the panels' Mortar regions (FBOs). Call before Skija teardown. */
    public void dispose() {
        fileBar.close();
        transport.close();
        partList.close();
    }

    public void bindViewport(ModelPartManager partManager) {
        controller.bindViewport(partManager);
    }

    /**
     * Run {@code action} now if the clip is clean, otherwise after the user
     * chooses Save / Discard in the confirmation popup (Cancel drops it).
     */
    public void confirmDiscardThen(Runnable action) {
        if (action == null) return;
        if (!controller.state().dirty()) {
            action.run();
            return;
        }
        pendingDiscardAction = action;
        openDiscardPopup = true;
        visible.set(true);
    }

    /** Per-frame entry point. Caller passes deltaTime so playback can advance. */
    public void render(float deltaTime) {
        if (!visible.get()) return;

        controller.tickPlayback(deltaTime);

        ImGui.setNextWindowSize(1100, 600, ImGuiCond.FirstUseEver);
        if (!ImGui.begin(WINDOW_TITLE, visible, ImGuiWindowFlags.NoCollapse)) {
            ImGui.end();
            handleCloseRequest();
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

        renderDiscardPopup();

        ImGui.end();
        handleCloseRequest();
    }

    /** The window's X was clicked: close only once unsaved changes are resolved. */
    private void handleCloseRequest() {
        if (visible.get()) return;
        if (controller.state().dirty() && pendingDiscardAction == null) {
            confirmDiscardThen(() -> {
                visible.set(false);
                controller.endSession();
            });
            return;
        }
        if (pendingDiscardAction == null) {
            controller.endSession();
        } else {
            // A popup is pending; keep the window alive until it is answered.
            visible.set(true);
        }
    }

    private void renderDiscardPopup() {
        if (openDiscardPopup) {
            ImGui.openPopup(DISCARD_POPUP);
            openDiscardPopup = false;
        }
        if (!ImGui.beginPopupModal(DISCARD_POPUP, ImGuiWindowFlags.AlwaysAutoResize)) {
            return;
        }
        String name = controller.state().clip().name();
        ImGui.text("'" + name + "' has unsaved changes.");
        ImGui.spacing();
        if (ImGui.button("Save", 90, 0)) {
            Runnable action = pendingDiscardAction;
            pendingDiscardAction = null;
            ImGui.closeCurrentPopup();
            if (controller.state().filePath() != null) {
                if (controller.save() && action != null) action.run();
            } else {
                // Save As is asynchronous (native dialog); continue only once saved.
                fileBar.promptSaveAs();
                if (!controller.state().dirty() && action != null) action.run();
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Discard", 90, 0)) {
            Runnable action = pendingDiscardAction;
            pendingDiscardAction = null;
            ImGui.closeCurrentPopup();
            if (action != null) action.run();
        }
        ImGui.sameLine();
        if (ImGui.button("Cancel", 90, 0)) {
            pendingDiscardAction = null;
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    private void handleShortcuts() {
        // The timeline and part list are child windows: a click on a keyframe
        // focuses the child, so test the whole window family, not just the root.
        if (!ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows)
                && !ImGui.isWindowHovered(ImGuiHoveredFlags.ChildWindows)) {
            return;
        }
        // A text field owns the keyboard: no editor shortcuts at all — not even
        // Ctrl+C/V, which must keep their text-editing meaning there.
        if (ImGui.getIO().getWantTextInput()) return;

        boolean ctrl = ImGui.getIO().getKeyCtrl();
        boolean shift = ImGui.getIO().getKeyShift();
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.Z)) {
            controller.undo();
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.Y)) {
            controller.redo();
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.S)) {
            fileBar.requestSave();
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.C)) {
            controller.copySelection();
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.V)) {
            controller.pasteAtPlayhead();
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.A)) {
            controller.selectAll();
        } else if (ctrl && ImGui.isKeyPressed(ImGuiKey.D)) {
            controller.duplicateSelectionToPlayhead();
        }
        if (ctrl) return;

        int stride = shift ? 5 : 1;
        if (ImGui.isKeyPressed(ImGuiKey.LeftArrow)) {
            controller.stepFrames(-stride);
        }
        if (ImGui.isKeyPressed(ImGuiKey.RightArrow)) {
            controller.stepFrames(stride);
        }

        if (ImGui.isKeyPressed(ImGuiKey.Space)) {
            controller.state().setPlaying(!controller.state().playing());
        }
        if (ImGui.isKeyPressed(ImGuiKey.K)) {
            String partId = controller.state().selectedPartId();
            if (partId != null) controller.insertKeyframeAtPlayhead(partId);
        }
        if (ImGui.isKeyPressed(ImGuiKey.Delete)) {
            if (controller.state().selection().size() > 1) {
                controller.deleteSelectedKeyframes();
            } else {
                String partId = controller.state().selectedPartId();
                int idx = controller.state().selectedKeyframeIndex();
                if (partId != null && idx >= 0) {
                    controller.deleteKeyframe(partId, idx);
                    controller.state().setSelectedKeyframeIndex(-1);
                }
            }
        }
    }
}
