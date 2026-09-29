package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
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

        // Center once when the popup appears (pivot 0.5) on the viewport it opens from.
        ImGui.setNextWindowPos(openCenterX, openCenterY, ImGuiCond.Appearing, 0.5f, 0.5f);
        ImGui.setNextWindowSize(DIALOG_WIDTH, 0, ImGuiCond.Appearing);

        if (ImGui.beginPopupModal(POPUP_ID,
                ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings)) {
            ImGui.textWrapped("Permanently delete '" + targetEntry.name() + "' ("
                    + targetEntry.type().label() + ") from disk?");
            ImGui.textDisabled(targetEntry.pathString());
            ImGui.spacing();

            ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.4f, 0.4f, 1.0f);
            ImGui.textWrapped("Warning: This permanently deletes the file from disk. This cannot be undone.");
            ImGui.popStyleColor();

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Keys: Escape cancels (no callback), Enter/KeypadEnter confirms.
            // Repeat off on the confirm keys — holding Enter past key-repeat delay
            // (~0.3 s) must not confirm the delete without the user choosing it.
            if (ImGui.isKeyPressed(ImGuiKey.Escape)) {
                cancel();
            } else if (ImGui.isKeyPressed(ImGuiKey.Enter, false)
                    || ImGui.isKeyPressed(ImGuiKey.KeypadEnter, false)) {
                confirmDelete();
            }

            if (isOpen) {
                // Centered button row
                float buttonWidth = 110.0f;
                float buttonHeight = 26.0f;
                float spacing = 10.0f;
                float totalWidth = buttonWidth * 2 + spacing;
                ImGui.setCursorPosX((ImGui.getWindowWidth() - totalWidth) / 2);

                // Delete button (red)
                if (EditorWidgets.dangerButton("Delete", buttonWidth, buttonHeight)) {
                    confirmDelete();
                }

                ImGui.sameLine(0, spacing);

                if (ImGui.button("Cancel", buttonWidth, buttonHeight)) {
                    cancel();
                }
            }

            ImGui.endPopup();
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
