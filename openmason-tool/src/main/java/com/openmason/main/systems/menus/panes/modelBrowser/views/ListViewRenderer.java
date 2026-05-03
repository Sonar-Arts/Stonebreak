package com.openmason.main.systems.menus.panes.modelBrowser.views;

import com.openmason.engine.format.omo.OMOFileManager;
import com.openmason.engine.format.sbt.SBTFileManager;
import com.openmason.main.systems.menus.panes.modelBrowser.ModelBrowserController;
import com.openmason.main.systems.menus.panes.modelBrowser.ModelBrowserState;
import com.openmason.main.systems.menus.panes.modelBrowser.sorting.SortBy;
import com.openmason.main.systems.menus.panes.modelBrowser.sorting.SortOrder;
import com.openmason.main.systems.menus.panes.modelBrowser.thumbnails.ModelBrowserThumbnailCache;
import com.openmason.main.systems.menus.panes.modelBrowser.thumbnails.ModelThumbnailRenderer;
import com.openmason.main.systems.menus.panes.modelBrowser.thumbnails.SBTThumbnailRenderer;
import imgui.ImGui;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Sortable table view of file-backed assets in the Model Browser. */
public class ListViewRenderer implements ViewRenderer {

    private static final int THUMBNAIL_SIZE = ModelBrowserThumbnailCache.SIZE_MEDIUM;
    private static final float THUMBNAIL_COLUMN_WIDTH = 40.0f;
    private static final float NAME_COLUMN_WIDTH = 240.0f;
    private static final float TYPE_COLUMN_WIDTH = 110.0f;

    private final ModelBrowserController controller;
    private final ModelBrowserThumbnailCache thumbnailCache;
    private final ModelThumbnailRenderer modelRenderer;
    private final SBTThumbnailRenderer sbtRenderer;

    public ListViewRenderer(ModelBrowserController controller) {
        this.controller = controller;
        this.thumbnailCache = new ModelBrowserThumbnailCache();
        this.modelRenderer = new ModelThumbnailRenderer(thumbnailCache);
        this.sbtRenderer = new SBTThumbnailRenderer(thumbnailCache);
    }

    @Override
    public void render() {
        ModelBrowserState state = controller.getState();
        List<ListItem> items = collect(state.getSelectedCategory());
        if (state.isSearchActive()) items = filterBySearch(items);
        items = sort(items, state.getSortBy(), state.getSortOrder());

        if (items.isEmpty()) {
            ImGui.spacing();
            ImGui.textDisabled("No assets in this folder.");
            return;
        }

        int flags = ImGuiTableFlags.Resizable
                | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.BordersOuter
                | ImGuiTableFlags.ScrollY;

        if (ImGui.beginTable("##ListViewTable", 4, flags)) {
            ImGui.tableSetupColumn("Icon", ImGuiTableColumnFlags.WidthFixed, THUMBNAIL_COLUMN_WIDTH);
            ImGui.tableSetupColumn("Name", ImGuiTableColumnFlags.WidthFixed, NAME_COLUMN_WIDTH);
            ImGui.tableSetupColumn("Type", ImGuiTableColumnFlags.WidthFixed, TYPE_COLUMN_WIDTH);
            ImGui.tableSetupColumn("Path", ImGuiTableColumnFlags.WidthStretch);
            ImGui.tableSetupScrollFreeze(0, 1);
            ImGui.tableHeadersRow();

            for (ListItem item : items) {
                ImGui.tableNextRow();

                ImGui.tableSetColumnIndex(0);
                int textureId = thumbnailFor(item);
                if (textureId > 0) {
                    ImGui.image(textureId, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
                } else {
                    ImGui.dummy(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
                }

                ImGui.tableSetColumnIndex(1);
                if (ImGui.selectable(item.displayName(), false, 0, 0, 0)) {
                    handleClick(item);
                }

                ImGui.tableSetColumnIndex(2);
                ImGui.text(item.kind() == ListItem.Kind.OMO ? ".OMO Model" : ".SBT Texture");

                ImGui.tableSetColumnIndex(3);
                ImGui.textDisabled(item.id());
            }

            ImGui.endTable();
        }
    }

    private List<ListItem> collect(String category) {
        List<ListItem> items = new ArrayList<>();
        boolean wantOMO = category.equals("All Assets") || category.equals(".OMO Models");
        boolean wantSBT = category.equals("All Assets") || category.equals(".SBT Textures");
        boolean wantRecent = category.equals("Recent Files");

        if (wantRecent) {
            for (String name : controller.getState().getRecentFiles()) {
                for (OMOFileManager.OMOFileEntry e : controller.getOMOFiles()) {
                    if (e.name().equals(name)) items.add(ListItem.omo(e));
                }
                for (SBTFileManager.SBTFileEntry e : controller.getSBTFiles()) {
                    if (e.name().equals(name)) items.add(ListItem.sbt(e));
                }
            }
            return items;
        }
        if (wantOMO) {
            for (OMOFileManager.OMOFileEntry e : controller.getOMOFiles()) items.add(ListItem.omo(e));
        }
        if (wantSBT) {
            for (SBTFileManager.SBTFileEntry e : controller.getSBTFiles()) items.add(ListItem.sbt(e));
        }
        return items;
    }

    private List<ListItem> filterBySearch(List<ListItem> items) {
        List<ListItem> filtered = new ArrayList<>();
        for (ListItem item : items) {
            if (controller.getState().matchesSearch(item.displayName())) filtered.add(item);
        }
        return filtered;
    }

    private List<ListItem> sort(List<ListItem> items, SortBy sortBy, SortOrder order) {
        Comparator<ListItem> comparator = switch (sortBy) {
            case TYPE -> Comparator.comparing((ListItem i) -> i.kind().ordinal())
                    .thenComparing(ListItem::displayName);
            case CATEGORY, RECENT, NAME -> Comparator.comparing(ListItem::displayName);
        };
        if (order == SortOrder.DESCENDING) comparator = comparator.reversed();
        List<ListItem> sorted = new ArrayList<>(items);
        sorted.sort(comparator);
        return sorted;
    }

    private int thumbnailFor(ListItem item) {
        return switch (item.kind()) {
            case OMO -> modelRenderer.getThumbnail(item.omoEntry(), THUMBNAIL_SIZE);
            case SBT -> sbtRenderer.getThumbnail(item.sbtEntry(), THUMBNAIL_SIZE);
        };
    }

    private void handleClick(ListItem item) {
        switch (item.kind()) {
            case OMO -> controller.selectOMOFile(item.omoEntry());
            case SBT -> controller.selectSBTFile(item.sbtEntry());
        }
    }

    @Override
    public void cleanup() {
        thumbnailCache.cleanup();
    }

    private record ListItem(Kind kind,
                            OMOFileManager.OMOFileEntry omoEntry,
                            SBTFileManager.SBTFileEntry sbtEntry) {
        static ListItem omo(OMOFileManager.OMOFileEntry e) { return new ListItem(Kind.OMO, e, null); }
        static ListItem sbt(SBTFileManager.SBTFileEntry e) { return new ListItem(Kind.SBT, null, e); }

        String displayName() {
            return kind == Kind.OMO ? omoEntry.name() : sbtEntry.name();
        }

        String id() {
            return kind == Kind.OMO ? omoEntry.getFilePathString() : sbtEntry.getFilePathString();
        }

        enum Kind { OMO, SBT }
    }
}
