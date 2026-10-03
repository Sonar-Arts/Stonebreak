package com.openmason.main.systems.menus.mainHub.dialogs;

import com.openmason.main.systems.menus.dialogs.ModalDialogs;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal dialog for renaming a project.
 * Single Responsibility: Capture a new project name from user input.
 */
public class RenameProjectDialog {

    private static final Logger logger = LoggerFactory.getLogger(RenameProjectDialog.class);
    private static final String POPUP_ID = "Rename Project##renameProject";
    private static final float DIALOG_WIDTH = 400.0f;

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

        if (ModalDialogs.begin(POPUP_ID, DIALOG_WIDTH)) {
            ImGui.text("Enter a new name for this project:");
            ImGui.spacing();

            if (focusInput) {
                ImGui.setKeyboardFocusHere();
                focusInput = false;
            }

            ImGui.setNextItemWidth(-1);
            // Use CallbackResize so the ImString stays in sync with edits
            ImGui.inputText("##rename_input", nameBuffer, ImGuiInputTextFlags.CallbackResize);

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

            if (!validName) {
                ThemedWidgets.inlineError("Name cannot be empty.");
            }

            ModalDialogs.buttonsBegin();
            // Enter confirms even while the name field has focus.
            if (ModalDialogs.primary("Rename", validName, true)) {
                logger.debug("Project {} renamed to '{}'", projectId, currentValue);
                if (callback != null) {
                    callback.onRename(projectId, currentValue);
                }
                isOpen = false;
                ImGui.closeCurrentPopup();
            }
            if (ModalDialogs.cancel()) {
                isOpen = false;
                ImGui.closeCurrentPopup();
            }
            ModalDialogs.end();
        }
    }

    public boolean isOpen() {
        return isOpen;
    }
}
