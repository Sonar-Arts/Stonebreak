package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confirmation dialog for permanently deleting a Project Browser asset
 * (.OMO model or .OMT texture) from disk.
 * Single Responsibility: Confirm deletion intent for one asset file.
 */
public class DeleteAssetDialog {

    private static final Logger logger = LoggerFactory.getLogger(DeleteAssetDialog.class);
    private static final String POPUP_ID = "Delete Asset##deleteAssetDialog";
    private static final float DIALOG_WIDTH = 460.0f;

    private boolean isOpen = false;
    private AssetEntry targetEntry;
    private DeleteCallback callback;

    // Viewport center captured at show() time — the trigger fires from the browser
    // window's render (pop-outs included), so the popup centers on the viewport it opens from.
    private float openCenterX;
    private float openCenterY;

    /**
     * Callback invoked when the user confirms deletion.
     */
    @FunctionalInterface
    public interface DeleteCallback {
        void onDelete(AssetEntry entry);
    }

    /**
     * Show the delete confirmation dialog. Precondition: called from inside a
     * window submission (the Project Browser's render), so the opening window's
     * viewport can be read; falls back to the main viewport otherwise.
     *
     * @param entry    the asset file to delete
     * @param callback invoked on confirmation
     */
    public void show(AssetEntry entry, DeleteCallback callback) {
        this.targetEntry = entry;
        this.callback = callback;
        imgui.ImGuiViewport openingViewport = ImGui.getWindowViewport();
        if (openingViewport != null) {
            this.openCenterX = openingViewport.getCenterX();
            this.openCenterY = openingViewport.getCenterY();
        } else {
            this.openCenterX = ImGui.getMainViewport().getCenterX();
            this.openCenterY = ImGui.getMainViewport().getCenterY();
        }
        this.isOpen = true;
        logger.debug("Delete dialog opened for asset: {}", entry.pathString());
    }

    /**
     * Render the dialog. Call every frame from the render loop.
     */
    public void render() {
        if (!isOpen || targetEntry == null) {
            return;
        }

        if (ModalDialogs.begin(POPUP_ID, new ModalDialogs.Anchor(openCenterX, openCenterY), DIALOG_WIDTH)) {
            ImGui.textWrapped("Permanently delete '" + targetEntry.name() + "' ("
                    + targetEntry.type().label() + ") from disk?");
            ImGui.textDisabled(targetEntry.pathString());
            ImGui.spacing();

            ThemedWidgets.statusTextWrapped(ThemeColors.Tone.ERROR,
                    "This permanently deletes the file from disk. This cannot be undone.");

            ModalDialogs.buttonsBegin();
            // Enter confirms (no key repeat, so a held Enter cannot delete).
            if (ModalDialogs.danger("Delete", true)) {
                confirmDelete();
            }
            if (ModalDialogs.cancel()) {
                cancel();
            }
            ModalDialogs.end();
        }

        // Re-open: the show trigger fires from another window's render, so the
        // popup may not have existed yet when openPopup was first implied.
        if (isOpen && !ImGui.isPopupOpen(POPUP_ID)) {
            ImGui.openPopup(POPUP_ID);
        }
    }

    private void cancel() {
        logger.debug("Asset delete cancelled: {}", targetEntry.pathString());
        isOpen = false;
        ImGui.closeCurrentPopup();
    }

    private void confirmDelete() {
        logger.debug("Asset delete confirmed: {}", targetEntry.pathString());
        if (callback != null) {
            callback.onDelete(targetEntry);
        }
        isOpen = false;
        ImGui.closeCurrentPopup();
    }

    public boolean isOpen() {
        return isOpen;
    }
}
