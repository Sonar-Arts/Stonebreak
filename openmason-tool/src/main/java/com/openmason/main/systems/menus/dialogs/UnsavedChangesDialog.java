package com.openmason.main.systems.menus.dialogs;

import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confirmation dialog shown when the user attempts to close a project or exit the application
 * while there are unsaved changes. Offers three choices: Save, Don't Save, Cancel.
 */
public class UnsavedChangesDialog {

    private static final Logger logger = LoggerFactory.getLogger(UnsavedChangesDialog.class);
    private static final String POPUP_ID = "Unsaved Changes";
    private static final float DIALOG_WIDTH = 480.0f;
    private static final float DIALOG_HEIGHT = 170.0f;

    private boolean isOpen = false;

    private Runnable onSave;
    private Runnable onDiscard;
    private Runnable onCancel;

    /**
     * Configure callbacks for dialog actions.
     *
     * @param onSave    called when the user chooses "Save" (save then proceed)
     * @param onDiscard called when the user chooses "Don't Save" (proceed without saving)
     * @param onCancel  called when the user chooses "Cancel" (abort the close/exit)
     */
    public void setCallbacks(Runnable onSave, Runnable onDiscard, Runnable onCancel) {
        this.onSave = onSave;
        this.onDiscard = onDiscard;
        this.onCancel = onCancel;
    }

    /**
     * Show the dialog.
     */
    public void show() {
        this.isOpen = true;
        logger.debug("Unsaved changes dialog opened");
    }

    /**
     * Render the dialog. Call every frame from the render loop.
     */
    public void render() {
        if (!isOpen) {
            return;
        }

        ImGui.setNextWindowSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        ImGui.setNextWindowPos(
                ImGui.getMainViewport().getCenterX() - DIALOG_WIDTH / 2,
                ImGui.getMainViewport().getCenterY() - DIALOG_HEIGHT / 2
        );

        if (ImGui.beginPopupModal(POPUP_ID, ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove)) {
            ImGui.spacing();
            ImGui.textWrapped("Do you want to save your project before closing?");
            ImGui.spacing();
            ImGui.textWrapped("Any unsaved changes will be lost.");
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            float buttonWidth = 120.0f;
            float spacing = 10.0f;
            float totalWidth = buttonWidth * 3 + spacing * 2;
            ImGui.setCursorPosX((DIALOG_WIDTH - totalWidth) / 2);

            // Save button (accent-colored)
            ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.ButtonActive);
            ImGui.pushStyleColor(ImGuiCol.Button, accent.x * 0.8f, accent.y * 0.8f, accent.z * 0.8f, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent.x, accent.y, accent.z, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x * 0.6f, accent.y * 0.6f, accent.z * 0.6f, 1.0f);
            if (ImGui.button("Save", buttonWidth, 0)) {
                logger.debug("Unsaved changes dialog: Save selected");
                isOpen = false;
                ImGui.closeCurrentPopup();
                if (onSave != null) {
                    onSave.run();
                }
            }
            ImGui.popStyleColor(3);

            ImGui.sameLine(0, spacing);

            if (ImGui.button("Don't Save", buttonWidth, 0)) {
                logger.debug("Unsaved changes dialog: Don't Save selected");
                isOpen = false;
                ImGui.closeCurrentPopup();
                if (onDiscard != null) {
                    onDiscard.run();
                }
            }

            ImGui.sameLine(0, spacing);

            if (ImGui.button("Cancel", buttonWidth, 0)) {
                logger.debug("Unsaved changes dialog: Cancel selected");
                isOpen = false;
                ImGui.closeCurrentPopup();
                if (onCancel != null) {
                    onCancel.run();
                }
            }

            ImGui.endPopup();
        }

        if (isOpen && !ImGui.isPopupOpen(POPUP_ID)) {
            ImGui.openPopup(POPUP_ID);
        }
    }

    public boolean isOpen() {
        return isOpen;
    }
}
