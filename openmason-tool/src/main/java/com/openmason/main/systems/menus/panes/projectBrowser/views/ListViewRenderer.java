package com.openmason.main.systems.menus.panes.projectBrowser.views;

import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectBrowserController;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.ModelThumbnailRenderer;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.OMTThumbnailRenderer;
import com.openmason.main.systems.menus.panes.projectBrowser.thumbnails.ThumbnailCache;
import imgui.ImGui;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;

import java.util.List;

/** Sortable table view of the assets in the open project's folder. */
public class ListViewRenderer implements ViewRenderer {

    private static final int THUMBNAIL_SIZE = ThumbnailCache.SIZE_MEDIUM;
    private static final float THUMBNAIL_COLUMN_WIDTH = 40.0f;
    private static final float NAME_COLUMN_WIDTH = 240.0f;
    private static final float TYPE_COLUMN_WIDTH = 110.0f;

    private final ProjectBrowserController controller;
    private final ModelThumbnailRenderer modelRenderer;
    private final OMTThumbnailRenderer omtRenderer;

    public ListViewRenderer(ProjectBrowserController controller,
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

        int flags = ImGuiTableFlags.Resizable
                | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.BordersOuter
                | ImGuiTableFlags.ScrollY;

        if (ImGui.beginTable("##ProjectListViewTable", 4, flags)) {
            ImGui.tableSetupColumn("Icon", ImGuiTableColumnFlags.WidthFixed, THUMBNAIL_COLUMN_WIDTH);
            ImGui.tableSetupColumn("Name", ImGuiTableColumnFlags.WidthFixed, NAME_COLUMN_WIDTH);
            ImGui.tableSetupColumn("Type", ImGuiTableColumnFlags.WidthFixed, TYPE_COLUMN_WIDTH);
            ImGui.tableSetupColumn("Path", ImGuiTableColumnFlags.WidthStretch);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            for (AssetEntry item : items) {
                ImGui.tableNextRow();

                ImGui.tableSetColumnIndex(0);
                int textureId = thumbnailFor(item);
                if (textureId > 0) {
                    ImGui.image(textureId, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
                } else {
                    ImGui.dummy(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
                }

                ImGui.tableSetColumnIndex(1);
                if (ImGui.selectable(item.name() + "##" + item.pathString(), false, 0, 0, 0)) {
                    controller.selectAsset(item);
                }

                ImGui.tableSetColumnIndex(2);
                ImGui.text(item.type().label());

                ImGui.tableSetColumnIndex(3);
                ImGui.textDisabled(item.pathString());
            }

            ImGui.endTable();
        }
    }

    private int thumbnailFor(AssetEntry item) {
        return switch (item.type()) {
            case OMO -> modelRenderer.getThumbnail(item, THUMBNAIL_SIZE);
            case OMT -> omtRenderer.getThumbnail(item, THUMBNAIL_SIZE);
        };
    }
}
