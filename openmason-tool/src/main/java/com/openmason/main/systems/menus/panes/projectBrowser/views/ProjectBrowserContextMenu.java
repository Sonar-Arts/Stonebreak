package com.openmason.main.systems.menus.panes.projectBrowser.views;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetType;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectBrowserController;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.ThumbnailCache;
import imgui.ImGui;

/**
 * Shared per-entry right-click context menu for the Project Browser's view
 * modes (grid, list, compact). Owns the popup IDs so all views route the
 * same per-entry popup, and carries the Select / Copy / Refresh Thumbnail /
 * Delete… entries.
 */
public class ProjectBrowserContextMenu {

    private static final String POPUP_PREFIX = "##AssetContextMenu_";

    private final ProjectBrowserController controller;
    private final ThumbnailCache thumbnailCache;

    public ProjectBrowserContextMenu(ProjectBrowserController controller, ThumbnailCache thumbnailCache) {
        this.controller = controller;
        this.thumbnailCache = thumbnailCache;
    }

    /** Open the per-entry popup; call when the entry is right-clicked. */
    public void openPopup(AssetEntry item) {
        ImGui.openPopup(POPUP_PREFIX + item.pathString());
    }

    /** Begin (and render into) the per-entry popup; pair with {@code ImGui.endPopup()}. */
    public boolean beginPopup(AssetEntry item) {
        return ImGui.beginPopup(POPUP_PREFIX + item.pathString());
    }

    /** Render the menu entries into the open popup. */
    public void render(AssetEntry item, int thumbnailSize) {
        ImGui.text(item.name());
        ImGui.separator();
        if (ImGui.menuItem("Select")) {
            controller.selectAsset(item);
            ImGui.closeCurrentPopup();
        }
        if (ImGui.menuItem("Copy Name")) {
            ImGui.setClipboardText(item.name());
            ImGui.closeCurrentPopup();
        }
        if (ImGui.menuItem("Copy Path")) {
            ImGui.setClipboardText(item.pathString());
            ImGui.closeCurrentPopup();
        }
        ImGui.separator();
        if (item.type() != AssetType.OMSC && item.type() != AssetType.OMUI) {
            if (ImGui.menuItem("Refresh Thumbnail")) {
                String key = item.type() == AssetType.OMO
                        ? ThumbnailCache.omoKey(item.pathString(), thumbnailSize)
                        : ThumbnailCache.omtKey(item.pathString(), thumbnailSize);
                thumbnailCache.invalidate(key);
                ImGui.closeCurrentPopup();
            }
            // Three dots (tool convention): opens a follow-up confirmation dialog.
            if (ImGui.menuItem("Delete...")) {
                controller.requestDelete(item);
                ImGui.closeCurrentPopup();
            }
        }
    }
}
