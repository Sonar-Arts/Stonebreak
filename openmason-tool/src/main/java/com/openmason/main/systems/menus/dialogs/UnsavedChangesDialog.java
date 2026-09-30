package com.openmason.main.systems.menus.dialogs;

import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confirmation dialog shown when the user attempts to close a project or exit the application
 * while there are unsaved changes. Offers three choices: Save, Don't Save, Cancel.
 */
public class UnsavedChangesDialog {

    private static final Logger logger = LoggerFactory.getLogger(UnsavedChangesDialog.class);
    private static final String POPUP_ID = "Unsaved Changes##unsavedChanges";
    private static final float DIALOG_WIDTH = 420.0f;

    private boolean isOpen = false;

    private Runnable onSave;
    private Runnable onDiscard;
    private Runnable onCancel;

    /** Callbacks for the prompt currently showing, when it is not the exit prompt. */
    private Runnable onceSave;
    private Runnable onceDiscard;
    private Runnable onceCancel;

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
        this.onceSave = null;
        this.onceDiscard = null;
        this.onceCancel = null;
        this.isOpen = true;
        logger.debug("Unsaved changes dialog opened");
    }

    /**
     * Show the dialog for one decision that is not exit (opening another project, say),
     * with its own callbacks. The default callbacks from {@link #setCallbacks} are left
     * untouched for the next {@link #show()}.
     */
    public void showOnce(Runnable onSave, Runnable onDiscard, Runnable onCancel) {
        this.onceSave = onSave;
        this.onceDiscard = onDiscard;
        this.onceCancel = onCancel;
        this.isOpen = true;
        logger.debug("Unsaved changes dialog opened (one-shot)");
    }

    private void fire(Runnable once, Runnable fallback) {
        Runnable target = once != null ? once : fallback;
        onceSave = null;
        onceDiscard = null;
        onceCancel = null;
        if (target != null) {
            target.run();
        }
    }

    /**
     * Render the dialog. Call every frame from the render loop.
     */
    public void render() {
        if (!isOpen) {
            return;
        }

        if (ModalDialogs.begin(POPUP_ID, DIALOG_WIDTH)) {
            ImGui.textWrapped("Do you want to save your project before closing?");
            ImGui.spacing();
            ImGui.textWrapped("Any unsaved changes will be lost.");

            ModalDialogs.buttonsBegin();
            if (ModalDialogs.primary("Save", true)) {
                logger.debug("Unsaved changes dialog: Save selected");
                isOpen = false;
                ImGui.closeCurrentPopup();
                fire(onceSave, onSave);
            }
            if (ModalDialogs.secondary("Don't Save")) {
                logger.debug("Unsaved changes dialog: Don't Save selected");
                isOpen = false;
                ImGui.closeCurrentPopup();
                fire(onceDiscard, onDiscard);
            }
            if (ModalDialogs.cancel()) {
                logger.debug("Unsaved changes dialog: Cancel selected");
                isOpen = false;
                ImGui.closeCurrentPopup();
                fire(onceCancel, onCancel);
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
