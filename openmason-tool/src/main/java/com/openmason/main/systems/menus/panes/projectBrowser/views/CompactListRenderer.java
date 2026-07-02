package com.openmason.main.systems.menus.panes.projectBrowser.views;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectBrowserController;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.ModelThumbnailRenderer;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.OMTThumbnailRenderer;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.ThumbnailCache;
import imgui.ImGui;
import imgui.flag.ImGuiStyleVar;

import java.util.List;

/** Dense single-line view of the assets in the open project's folder. */
public class CompactListRenderer implements ViewRenderer {

    private static final int THUMBNAIL_SIZE = ThumbnailCache.SIZE_SMALL;
    private static final float ITEM_SPACING = 2.0f;
    private static final float ICON_TEXT_SPACING = 4.0f;

    private final ProjectBrowserController controller;
    private final ModelThumbnailRenderer modelRenderer;
    private final OMTThumbnailRenderer omtRenderer;

    public CompactListRenderer(ProjectBrowserController controller,
                               ModelThumbnailRenderer modelRenderer,
                               OMTThumbnailRenderer omtRenderer) {
        this.controller = controller;
        this.modelRenderer = modelRenderer;
        this.omtRenderer = omtRenderer;
    }

    @Override
    public void render() {
        List<AssetEntry> items = controller.getVisibleAssets();
        if (items.isEmpty()) {
            ImGui.spacing();
            ImGui.textDisabled("No assets in this project.");
            return;
        }

        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, ITEM_SPACING);
        for (AssetEntry item : items) {
            int textureId = thumbnailFor(item);
            if (textureId > 0) {
                ImGui.image(textureId, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
            } else {
                ImGui.dummy(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
            }
            ImGui.sameLine(0, ICON_TEXT_SPACING);
            if (ImGui.selectable(item.name() + "##" + item.pathString(), false)) {
                controller.selectAsset(item);
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip(item.pathString());
        }
        ImGui.popStyleVar();
    }

    private int thumbnailFor(AssetEntry item) {
        return switch (item.type()) {
            case OMO -> modelRenderer.getThumbnail(item, THUMBNAIL_SIZE);
            case OMT -> omtRenderer.getThumbnail(item, THUMBNAIL_SIZE);
        };
    }
}
