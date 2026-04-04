package com.openmason.main.systems.menus.mainHub.dialogs;

import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confirmation dialog for deleting a project.
 * Offers an option to also delete the .OMP file from disk.
 * Single Responsibility: Confirm deletion intent and capture delete-from-disk preference.
 */
public class DeleteProjectDialog {

    private static final Logger logger = LoggerFactory.getLogger(DeleteProjectDialog.class);
    private static final String POPUP_ID = "Delete Project?";
    private static final float DIALOG_WIDTH = 450.0f;
    private static final float DIALOG_HEIGHT = 210.0f;

    private boolean isOpen = false;
    private RecentProject targetProject;
    private final ImBoolean deleteFromDisk = new ImBoolean(false);
    private DeleteCallback callback;

    /**
     * Callback invoked when the user confirms deletion.
     */
    @FunctionalInterface
    public interface DeleteCallback {
        void onDelete(RecentProject project, boolean deleteFile);
    }

    /**
     * Show the delete confirmation dialog.
     *
     * @param project  the project to delete
     * @param callback invoked on confirmation
     */
    public void show(RecentProject project, DeleteCallback callback) {
        this.targetProject = project;
        this.callback = callback;
        this.deleteFromDisk.set(false);
        this.isOpen = true;
        logger.debug("Delete dialog opened for project: {}", project.getName());
    }

    /**
     * Render the dialog. Call every frame from the render loop.
     */
    public void render() {
        if (!isOpen || targetProject == null) {
            return;
        }

        ImGui.setNextWindowSize(DIALOG_WIDTH, DIALOG_HEIGHT);
        ImGui.setNextWindowPos(
                ImGui.getMainViewport().getCenterX() - DIALOG_WIDTH / 2,
                ImGui.getMainViewport().getCenterY() - DIALOG_HEIGHT / 2
        );

        if (ImGui.beginPopupModal(POPUP_ID, ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove)) {
            ImGui.textWrapped("Are you sure you want to remove '" + targetProject.getName()
                    + "' from recent projects?");
            ImGui.spacing();

            ImGui.checkbox("Also delete .OMP file from disk", deleteFromDisk);

            if (deleteFromDisk.get()) {
                ImGui.spacing();
                ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.4f, 0.4f, 1.0f);
                ImGui.textWrapped("Warning: This will permanently delete the project file. This cannot be undone.");
                ImGui.popStyleColor();
            }

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Button row
            float buttonWidth = 100.0f;
            float spacing = 10.0f;
            float totalWidth = buttonWidth * 2 + spacing;
            ImGui.setCursorPosX((DIALOG_WIDTH - totalWidth) / 2);

            // Delete button (red)
            ImGui.pushStyleColor(ImGuiCol.Button, 0.6f, 0.15f, 0.15f, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.7f, 0.2f, 0.2f, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.8f, 0.25f, 0.25f, 1.0f);
            if (ImGui.button("Delete", buttonWidth, 0)) {
                logger.debug("Project '{}' deletion confirmed (deleteFile={})",
                        targetProject.getName(), deleteFromDisk.get());
                if (callback != null) {
                    callback.onDelete(targetProject, deleteFromDisk.get());
                }
                isOpen = false;
                ImGui.closeCurrentPopup();
            }
            ImGui.popStyleColor(3);

            ImGui.sameLine(0, spacing);

            if (ImGui.button("Cancel", buttonWidth, 0)) {
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
