package com.openmason.main.systems.menus.animationEditor.panels;

import com.openmason.main.systems.menus.animationEditor.controller.AnimationEditorController;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * New / Open / Save / Save As row plus a right-aligned save status. Routes
 * file operations through {@link FileDialogService} so the native picker is
 * used consistently with the rest of the tool.
 */
public final class FileBarPanel {

    private static final Logger logger = LoggerFactory.getLogger(FileBarPanel.class);

    private final AnimationEditorController controller;
    private FileDialogService fileDialogService;

    public FileBarPanel(AnimationEditorController controller) {
        this.controller = controller;
    }

    public void setFileDialogService(FileDialogService service) {
        this.fileDialogService = service;
    }

    public void render() {
        if (ImGui.button("New")) {
            controller.newClip();
        }
        AnimUI.tooltip("Discard current clip and start a new untitled animation.");

        ImGui.sameLine();
        if (ImGui.button("Open...")) {
            promptOpen();
        }
        AnimUI.tooltip("Load an .oma animation from disk.");

        ImGui.sameLine();
        boolean hasPath = controller.state().filePath() != null;
        boolean canSave = hasPath && controller.state().dirty();
        AnimUI.beginDisabled(!canSave);
        if (ImGui.button("Save")) {
            controller.save();
        }
        AnimUI.endDisabled(!canSave);
        AnimUI.tooltip(hasPath ? "Save changes to the current file (Ctrl+S)." : "No file path set — use Save As.");

        ImGui.sameLine();
        if (ImGui.button("Save As...")) {
            promptSaveAs();
        }
        AnimUI.tooltip("Save the current clip to a new .oma file.");

        renderStatus();
    }

    public void promptOpen() {
        if (fileDialogService == null) {
            logger.warn("File dialog service not available — cannot open .oma");
            return;
        }
        fileDialogService.showOpenOMADialog(controller::load);
    }

    public void promptSaveAs() {
        if (fileDialogService == null) {
            logger.warn("File dialog service not available — cannot save .oma");
            return;
        }
        fileDialogService.showSaveOMADialog(controller::saveAs);
    }

    private void renderStatus() {
        String status = saveStatusLabel();
        String path = controller.state().filePath();
        String fullStatus = path != null ? status + "  " + path : status;

        ImGui.sameLine();
        float available = ImGui.getContentRegionAvailX();
        float textWidth = ImGui.calcTextSize(fullStatus).x;
        if (available > textWidth + 8f) {
            ImGui.dummy(available - textWidth - 8f, 0);
            ImGui.sameLine();
        }
        ImGui.textDisabled(fullStatus);
    }

    private String saveStatusLabel() {
        boolean hasPath = controller.state().filePath() != null;
        boolean dirty = controller.state().dirty();
        if (!hasPath) return "[unsaved]";
        return dirty ? "[modified]" : "[saved]";
    }
}
