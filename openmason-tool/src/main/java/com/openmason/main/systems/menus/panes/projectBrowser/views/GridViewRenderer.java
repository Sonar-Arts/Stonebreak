package com.openmason.main.systems.menus.panes.projectBrowser.views;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetType;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectBrowserController;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.ModelThumbnailRenderer;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.OMTThumbnailRenderer;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.ThumbnailCache;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;

import java.util.List;

/**
 * Grid view renderer for the Project Browser — large-icon view of the .OMO
 * and .OMT files in the open project's folder.
 */
public class GridViewRenderer implements ViewRenderer {

    private static final int THUMBNAIL_SIZE = ThumbnailCache.SIZE_LARGE;
    private static final float ITEM_WIDTH = 100.0f;
    private static final float PADDING = 8.0f;

    private final ProjectBrowserController controller;
    private final ThumbnailCache thumbnailCache;
    private final ModelThumbnailRenderer modelRenderer;
    private final OMTThumbnailRenderer omtRenderer;

    public GridViewRenderer(ProjectBrowserController controller,
                            ThumbnailCache thumbnailCache,
                            ModelThumbnailRenderer modelRenderer,
                            OMTThumbnailRenderer omtRenderer) {
        this.controller = controller;
        this.thumbnailCache = thumbnailCache;
        this.modelRenderer = modelRenderer;
        this.omtRenderer = omtRenderer;
    }

    @Override
    public void render() {
        List<AssetEntry> items = controller.getVisibleAssets();
        if (items.isEmpty()) {
            renderEmptyState();
            return;
        }

        ImVec2 region = ImGui.getContentRegionAvail();
        int columns = Math.max(1, (int) ((region.x + PADDING) / (ITEM_WIDTH + PADDING)));

        int column = 0;
        for (AssetEntry item : items) {
            if (column > 0) ImGui.sameLine();
            renderGridItem(item);
            column++;
            if (column >= columns) column = 0;
        }
    }

    private void renderGridItem(AssetEntry item) {
        ImGui.beginGroup();
        int textureId = thumbnailFor(item);
        if (textureId > 0) {
            ImGui.image(textureId, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        } else {
            ImGui.dummy(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        }

        if (ImGui.isItemClicked()) {
            controller.selectAsset(item);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(item.name() + "\n" + item.type().label());
            ImGui.getWindowDrawList().addRect(
                    ImGui.getItemRectMinX(), ImGui.getItemRectMinY(),
                    ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY(),
                    ImGui.getColorU32(ImGuiCol.HeaderHovered),
                    0.0f, 0, 2.0f
            );
        }
        if (ImGui.isItemClicked(1)) {
            ImGui.openPopup("##GridItemContextMenu_" + item.pathString());
        }

        float labelStartX = ImGui.getCursorPosX();
        String labelText = item.name();
        ImVec2 textSize = new ImVec2();
        ImGui.calcTextSize(textSize, labelText);
        if (textSize.x > THUMBNAIL_SIZE) {
            while (!labelText.isEmpty() && ImGui.calcTextSize(labelText + "...").x > THUMBNAIL_SIZE) {
                labelText = labelText.substring(0, labelText.length() - 1);
            }
            labelText += "...";
        }
        ImGui.text(labelText);
        ImGui.setCursorPosX(labelStartX);
        ImGui.dummy(ITEM_WIDTH, 0);
        ImGui.endGroup();

        if (ImGui.beginPopup("##GridItemContextMenu_" + item.pathString())) {
            renderContextMenu(item);
            ImGui.endPopup();
        }
    }

    private int thumbnailFor(AssetEntry item) {
        return switch (item.type()) {
            case OMO -> modelRenderer.getThumbnail(item, THUMBNAIL_SIZE);
            case OMT -> omtRenderer.getThumbnail(item, THUMBNAIL_SIZE);
        };
    }

    private void renderContextMenu(AssetEntry item) {
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
        if (ImGui.menuItem("Refresh Thumbnail")) {
            String key = item.type() == AssetType.OMO
                    ? ThumbnailCache.omoKey(item.pathString(), THUMBNAIL_SIZE)
                    : ThumbnailCache.omtKey(item.pathString(), THUMBNAIL_SIZE);
            thumbnailCache.invalidate(key);
            ImGui.closeCurrentPopup();
        }
    }

    private void renderEmptyState() {
        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();
        float w = ImGui.getContentRegionAvailX();
        String msg = "No assets in this project";
        ImGui.setCursorPosX((w - ImGui.calcTextSize(msg).x) * 0.5f);
        ImGui.textDisabled(msg);
        ImGui.spacing();
        String hint = controller.getState().isSearchActive()
                ? "Try clearing your search."
                : "Save .OMO or .OMT files into the project folder, then click Refresh.";
        ImGui.setCursorPosX((w - ImGui.calcTextSize(hint).x) * 0.5f);
        ImGui.text(hint);
    }
}
