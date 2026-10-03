package com.openmason.main.systems.menus.mainHub.dialogs;

import com.openmason.main.systems.menus.dialogs.ModalDialogs;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
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
    private static final String POPUP_ID = "Delete Project##deleteProject";
    private static final float DIALOG_WIDTH = 420.0f;

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

        if (ModalDialogs.begin(POPUP_ID, DIALOG_WIDTH)) {
            ImGui.textWrapped("Are you sure you want to remove '" + targetProject.getName()
                    + "' from recent projects?");
            ImGui.spacing();

            ImGui.checkbox("Also delete .OMP file from disk", deleteFromDisk);

            if (deleteFromDisk.get()) {
                ImGui.spacing();
                ThemedWidgets.statusTextWrapped(ThemeColors.Tone.ERROR,
                        "This will permanently delete the project file. This cannot be undone.");
            }

            ModalDialogs.buttonsBegin();
            if (ModalDialogs.danger("Delete", true)) {
                logger.debug("Project '{}' deletion confirmed (deleteFile={})",
                        targetProject.getName(), deleteFromDisk.get());
                if (callback != null) {
                    callback.onDelete(targetProject, deleteFromDisk.get());
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

        if (isOpen && !ImGui.isPopupOpen(POPUP_ID)) {
            ImGui.openPopup(POPUP_ID);
        }
    }

    public boolean isOpen() {
        return isOpen;
    }
}
