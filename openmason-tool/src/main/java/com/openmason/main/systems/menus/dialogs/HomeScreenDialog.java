package com.openmason.main.systems.menus.dialogs;

import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confirmation dialog shown when the user wants to return to the Home Screen.
 * Offers three choices: Save (save project then navigate), Don't Save (navigate without saving), Cancel (stay).
 */
public class HomeScreenDialog {

    private static final Logger logger = LoggerFactory.getLogger(HomeScreenDialog.class);
    private static final String POPUP_ID = "Return to Home Screen?";
    private static final float DIALOG_WIDTH = 480.0f;
    private static final float DIALOG_HEIGHT = 170.0f;

    private boolean isOpen = false;
    private boolean hasUnsavedChanges = false;

    private Runnable onSave;
    private Runnable onNavigate;

    /**
     * Configure callbacks for dialog actions.
     *
     * @param onSave     called when the user chooses "Save" (should save the project)
     * @param onNavigate called to transition to the home screen (called after save or on "Don't Save")
     */
    public void setCallbacks(Runnable onSave, Runnable onNavigate) {
        this.onSave = onSave;
        this.onNavigate = onNavigate;
    }

    /**
     * Show the dialog.
     *
     * @param hasUnsavedChanges whether the current project has unsaved changes
     */
    public void show(boolean hasUnsavedChanges) {
        this.hasUnsavedChanges = hasUnsavedChanges;
        this.isOpen = true;
        logger.debug("Home screen dialog opened (unsavedChanges={})", hasUnsavedChanges);
    }

    /**
     * Render the dialog. Call every frame from the render loop.
     */
    public void render() {
        if (!isOpen) {
            return;
        }

        // If there are no unsaved changes, skip straight to navigation
        if (!hasUnsavedChanges) {
            isOpen = false;
            if (onNavigate != null) {
                onNavigate.run();
            }
            return;
        }

        ImGui.setNextWindowSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        ImGui.setNextWindowPos(
                ImGui.getMainViewport().getCenterX() - DIALOG_WIDTH / 2,
                ImGui.getMainViewport().getCenterY() - DIALOG_HEIGHT / 2
        );

        if (ImGui.beginPopupModal(POPUP_ID, ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove)) {
            ImGui.spacing();
            ImGui.textWrapped("Do you want to save your project before returning to the Home Screen?");
            ImGui.spacing();
            ImGui.textWrapped("Any unsaved changes will be lost.");
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Three-button row: [Save]  [Don't Save]  [Cancel]
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
                logger.debug("Home screen dialog: Save selected");
                isOpen = false;
                ImGui.closeCurrentPopup();
                if (onSave != null) {
                    onSave.run();
                }
                if (onNavigate != null) {
                    onNavigate.run();
                }
            }
            ImGui.popStyleColor(3);

            ImGui.sameLine(0, spacing);

            if (ImGui.button("Don't Save", buttonWidth, 0)) {
                logger.debug("Home screen dialog: Don't Save selected");
                isOpen = false;
                ImGui.closeCurrentPopup();
                if (onNavigate != null) {
                    onNavigate.run();
                }
            }

            ImGui.sameLine(0, spacing);

            if (ImGui.button("Cancel", buttonWidth, 0)) {
                logger.debug("Home screen dialog: Cancel selected");
                isOpen = false;
                ImGui.closeCurrentPopup();
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
