package com.openmason.main.systems.menus.dialogs;

import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confirmation dialog shown when the user wants to return to the Home Screen.
 * Offers three choices: Save (save project then navigate), Don't Save (navigate without saving), Cancel (stay).
 */
public class HomeScreenDialog {

    private static final Logger logger = LoggerFactory.getLogger(HomeScreenDialog.class);
    private static final String POPUP_ID = "Return to Home Screen##homeScreen";
    private static final float DIALOG_WIDTH = 420.0f;

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

        if (ModalDialogs.begin(POPUP_ID, DIALOG_WIDTH)) {
            ImGui.textWrapped("Do you want to save your project before returning to the Home Screen?");
            ImGui.spacing();
            ImGui.textWrapped("Any unsaved changes will be lost.");

            ModalDialogs.buttonsBegin();
            if (ModalDialogs.primary("Save", true)) {
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
            if (ModalDialogs.secondary("Don't Save")) {
                logger.debug("Home screen dialog: Don't Save selected");
                isOpen = false;
                ImGui.closeCurrentPopup();
                if (onNavigate != null) {
                    onNavigate.run();
                }
            }
            if (ModalDialogs.cancel()) {
                logger.debug("Home screen dialog: Cancel selected");
                isOpen = false;
                ImGui.closeCurrentPopup();
            }
            ModalDialogs.end();
        }

        if (isOpen && !ImGui.isPopupOpen(POPUP_ID)) {
            ImGui.openPopup(POPUP_ID);
        }
    }

    public boolean isOpen() {
        return isOpen;
    }
}
