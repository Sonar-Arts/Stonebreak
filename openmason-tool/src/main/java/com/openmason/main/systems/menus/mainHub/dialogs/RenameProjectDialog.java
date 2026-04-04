package com.openmason.main.systems.menus.mainHub.dialogs;

import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal dialog for renaming a project.
 * Single Responsibility: Capture a new project name from user input.
 */
public class RenameProjectDialog {

    private static final Logger logger = LoggerFactory.getLogger(RenameProjectDialog.class);
    private static final String POPUP_ID = "Rename Project";
    private static final float DIALOG_WIDTH = 400.0f;
    private static final float DIALOG_HEIGHT = 150.0f;

    private boolean isOpen = false;
    private boolean needsOpen = false;
    private boolean focusInput = false;
    private ImString nameBuffer;
    private String projectId;
    private RenameCallback callback;

    /**
     * Callback invoked when the user confirms the rename.
     */
    @FunctionalInterface
    public interface RenameCallback {
        void onRename(String projectId, String newName);
    }

    /**
     * Show the rename dialog for a project.
     *
     * @param projectId   the ID of the project to rename
     * @param currentName the current project name (pre-fills the input)
     * @param callback    invoked on confirmation with the new name
     */
    public void show(String projectId, String currentName, RenameCallback callback) {
        this.projectId = projectId;
        this.callback = callback;
        this.nameBuffer = new ImString(currentName != null ? currentName : "", 256);
        this.isOpen = true;
        this.needsOpen = true;
        this.focusInput = true;
        logger.debug("Rename dialog opened for project: {}", projectId);
    }

    /**
     * Render the dialog. Call every frame from the render loop.
     */
    public void render() {
        if (!isOpen) {
            return;
        }

        if (needsOpen) {
            ImGui.openPopup(POPUP_ID);
            needsOpen = false;
        }

        ImGui.setNextWindowSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        ImGui.setNextWindowPos(
                ImGui.getMainViewport().getCenterX() - DIALOG_WIDTH / 2,
                ImGui.getMainViewport().getCenterY() - DIALOG_HEIGHT / 2
        );

        if (ImGui.beginPopupModal(POPUP_ID, ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove)) {
            ImGui.text("Enter a new name for this project:");
            ImGui.spacing();

            if (focusInput) {
                ImGui.setKeyboardFocusHere();
                focusInput = false;
            }

            ImGui.setNextItemWidth(-1);
            // Use CallbackResize so the ImString stays in sync with edits
            boolean enterPressed = ImGui.inputText("##rename_input", nameBuffer,
                    ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.CallbackResize);

            // Read the current input value directly from the native buffer.
            // ImString.get() can lag behind in-progress edits when the field is
            // still active (focused). Reading InputText's internal state via the
            // ImString data avoids that desync.
            String currentValue = new String(nameBuffer.getData()).trim();
            // Strip null characters that pad the ImString buffer
            int nullIdx = currentValue.indexOf('\0');
            if (nullIdx >= 0) {
                currentValue = currentValue.substring(0, nullIdx).trim();
            }

            boolean validName = !currentValue.isEmpty();

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Button row
            float buttonWidth = 100.0f;
            float spacing = 10.0f;
            float totalWidth = buttonWidth * 2 + spacing;
            ImGui.setCursorPosX((DIALOG_WIDTH - totalWidth) / 2);

            boolean shouldRename = false;
            if (!validName) {
                ImGui.beginDisabled();
            }
            if (ImGui.button("Rename", buttonWidth, 0)) {
                shouldRename = true;
            }
            if (!validName) {
                ImGui.endDisabled();
            }

            if ((shouldRename || enterPressed) && validName) {
                logger.debug("Project {} renamed to '{}'", projectId, currentValue);
                if (callback != null) {
                    callback.onRename(projectId, currentValue);
                }
                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ImGui.sameLine(0, spacing);

            if (ImGui.button("Cancel", buttonWidth, 0)) {
                isOpen = false;
                ImGui.closeCurrentPopup();
            }

            ImGui.endPopup();
        }
    }

    public boolean isOpen() {
        return isOpen;
    }
}
