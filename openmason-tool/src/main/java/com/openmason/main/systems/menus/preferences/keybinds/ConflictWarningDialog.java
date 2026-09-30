package com.openmason.main.systems.menus.preferences.keybinds;

import imgui.ImGui;
import com.openmason.main.systems.menus.dialogs.ModalDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal dialog for warning about keybind conflicts and offering resolution.
 * <p>
 * Displayed when a user attempts to assign a key combination that's already
 * bound to another action. Offers to reassign the key (clearing the old binding)
 * or cancel the operation.
 * </p>
 *
 * @author Open Mason Team
 */
public class ConflictWarningDialog {

    private static final Logger logger = LoggerFactory.getLogger(ConflictWarningDialog.class);

    private static final String POPUP_ID = "Keybind Conflict##keybindConflict";

    private boolean isVisible = false;
    private String conflictMessage = "";
    private Runnable onReassign = null;
    private Runnable onCancel = null;

    /**
     * Show the conflict warning dialog.
     *
     * @param newActionName      the name of the action that wants the keybind
     * @param existingActionName the name of the action currently using the keybind
     * @param keyDisplayName     the display name of the conflicting key (e.g., "Ctrl+S")
     * @param onReassign         callback to execute if user confirms reassignment
     * @param onCancel           callback to execute if user cancels
     */
    public void show(String newActionName,
                     String existingActionName,
                     String keyDisplayName,
                     Runnable onReassign,
                     Runnable onCancel) {
        this.isVisible = true;
        this.conflictMessage = String.format(
                "The key '%s' is already bound to:\n" +
                "  '%s'\n\n" +
                "Do you want to reassign it to:\n" +
                "  '%s'?\n\n" +
                "This will clear the old binding.",
                keyDisplayName,
                existingActionName,
                newActionName
        );
        this.onReassign = onReassign;
        this.onCancel = onCancel;
        logger.debug("Showing conflict warning: {} already bound to {}", keyDisplayName, existingActionName);
    }

    /**
     * Render the conflict warning dialog.
     * Call this in the main render loop.
     */
    public void render() {
        if (!isVisible) {
            return;
        }

        ModalDialogs.openIfNeeded(POPUP_ID);

        if (ModalDialogs.begin(POPUP_ID, 320)) {
            // Conflict message
            ImGui.textWrapped(conflictMessage);

            ModalDialogs.buttonsBegin();
            if (ModalDialogs.danger("Reassign", true)) {
                confirmReassignment();
            }
            if (ModalDialogs.cancel()) {
                cancel();
            }

            ModalDialogs.end();
        }
    }

    /**
     * Confirm the reassignment and close the dialog.
     */
    private void confirmReassignment() {
        isVisible = false;
        ImGui.closeCurrentPopup();
        if (onReassign != null) {
            onReassign.run();
            logger.debug("User confirmed keybind reassignment");
        }
    }

    /**
     * Cancel the operation and close the dialog.
     */
    private void cancel() {
        isVisible = false;
        ImGui.closeCurrentPopup();
        if (onCancel != null) {
            onCancel.run();
        }
        logger.debug("User cancelled keybind reassignment");
    }

    /**
     * Checks if the dialog is currently visible.
     *
     * @return true if visible
     */
    public boolean isVisible() {
        return isVisible;
    }

    /**
     * Hide the dialog without executing any callbacks.
     */
    public void hide() {
        isVisible = false;
        logger.debug("Conflict warning dialog hidden");
    }
}
