package com.openmason.main.systems.menus.preferences.keybinds;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
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

        // Open modal popup
        ImGui.openPopup("Keybind Conflict");

        // Center the modal
        ImGui.setNextWindowPos(
                ImGui.getIO().getDisplaySizeX() * 0.5f,
                ImGui.getIO().getDisplaySizeY() * 0.5f,
                0, // ImGuiCond.Always
                0.5f, 0.5f // pivot
        );

        // Begin modal popup
        if (ImGui.beginPopupModal("Keybind Conflict", ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoMove)) {
            // Warning icon (using text)
            ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.7f, 0.0f, 1.0f); // Orange
            ImGui.text("\u26A0"); // Warning triangle
            ImGui.popStyleColor();
            ImGui.sameLine();
            ImGui.text("Keybind Conflict");

            ImGui.separator();
            ImGui.spacing();

            // Conflict message
            ImGui.textWrapped(conflictMessage);
            ImGui.spacing();

            ImGui.separator();
            ImGui.spacing();

            // Buttons
            // Reassign button (highlighted)
            ImGui.pushStyleColor(ImGuiCol.Button, 0.8f, 0.4f, 0.0f, 1.0f); // Orange
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.9f, 0.5f, 0.1f, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.7f, 0.3f, 0.0f, 1.0f);
            if (ImGui.button("Reassign", 100, 0)) {
                confirmReassignment();
            }
            ImGui.popStyleColor(3);

            ImGui.sameLine();

            // Cancel button
            if (ImGui.button("Cancel", 100, 0)) {
                cancel();
            }

            ImGui.endPopup();
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
