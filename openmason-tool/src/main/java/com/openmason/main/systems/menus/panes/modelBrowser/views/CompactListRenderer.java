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
import imgui.flag.ImGuiStyleVar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Dense single-line view of file-backed assets in the Model Browser. */
public class CompactListRenderer implements ViewRenderer {

    private static final int THUMBNAIL_SIZE = ModelBrowserThumbnailCache.SIZE_SMALL;
    private static final float ITEM_SPACING = 2.0f;
    private static final float ICON_TEXT_SPACING = 4.0f;

    private final ModelBrowserController controller;
    private final ModelBrowserThumbnailCache thumbnailCache;
    private final ModelThumbnailRenderer modelRenderer;
    private final SBTThumbnailRenderer sbtRenderer;

    public CompactListRenderer(ModelBrowserController controller) {
        this.controller = controller;
        this.thumbnailCache = new ModelBrowserThumbnailCache();
        this.modelRenderer = new ModelThumbnailRenderer(thumbnailCache);
        this.sbtRenderer = new SBTThumbnailRenderer(thumbnailCache);
    }

    @Override
    public void render() {
        ModelBrowserState state = controller.getState();
        List<CompactItem> items = collect(state.getSelectedCategory());
        if (state.isSearchActive()) items = filterBySearch(items);
        items = sort(items, state.getSortBy(), state.getSortOrder());

        if (items.isEmpty()) {
            ImGui.spacing();
            ImGui.textDisabled("No assets in this folder.");
            return;
        }

        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, ITEM_SPACING);
        for (CompactItem item : items) {
            int textureId = thumbnailFor(item);
            if (textureId > 0) {
                ImGui.image(textureId, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
            } else {
                ImGui.dummy(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
            }
            ImGui.sameLine(0, ICON_TEXT_SPACING);
            if (ImGui.selectable(item.displayName(), false)) handleClick(item);
            if (ImGui.isItemHovered()) ImGui.setTooltip(item.id());
        }
        ImGui.popStyleVar();
    }

    private List<CompactItem> collect(String category) {
        List<CompactItem> items = new ArrayList<>();
        boolean wantOMO = category.equals("All Assets") || category.equals(".OMO Models");
        boolean wantSBT = category.equals("All Assets") || category.equals(".SBT Textures");
        boolean wantRecent = category.equals("Recent Files");

        if (wantRecent) {
            for (String name : controller.getState().getRecentFiles()) {
                for (OMOFileManager.OMOFileEntry e : controller.getOMOFiles()) {
                    if (e.name().equals(name)) items.add(CompactItem.omo(e));
                }
                for (SBTFileManager.SBTFileEntry e : controller.getSBTFiles()) {
                    if (e.name().equals(name)) items.add(CompactItem.sbt(e));
                }
            }
            return items;
        }
        if (wantOMO) {
            for (OMOFileManager.OMOFileEntry e : controller.getOMOFiles()) items.add(CompactItem.omo(e));
        }
        if (wantSBT) {
            for (SBTFileManager.SBTFileEntry e : controller.getSBTFiles()) items.add(CompactItem.sbt(e));
        }
        return items;
    }

    private List<CompactItem> filterBySearch(List<CompactItem> items) {
        List<CompactItem> filtered = new ArrayList<>();
        for (CompactItem item : items) {
            if (controller.getState().matchesSearch(item.displayName())) filtered.add(item);
        }
        return filtered;
    }

    private List<CompactItem> sort(List<CompactItem> items, SortBy sortBy, SortOrder order) {
        Comparator<CompactItem> comparator = switch (sortBy) {
            case TYPE -> Comparator.comparing((CompactItem i) -> i.kind().ordinal())
                    .thenComparing(CompactItem::displayName);
            case CATEGORY, RECENT, NAME -> Comparator.comparing(CompactItem::displayName);
        };
        if (order == SortOrder.DESCENDING) comparator = comparator.reversed();
        List<CompactItem> sorted = new ArrayList<>(items);
        sorted.sort(comparator);
        return sorted;
    }

    private int thumbnailFor(CompactItem item) {
        return switch (item.kind()) {
            case OMO -> modelRenderer.getThumbnail(item.omoEntry(), THUMBNAIL_SIZE);
            case SBT -> sbtRenderer.getThumbnail(item.sbtEntry(), THUMBNAIL_SIZE);
        };
    }

    private void handleClick(CompactItem item) {
        switch (item.kind()) {
            case OMO -> controller.selectOMOFile(item.omoEntry());
            case SBT -> controller.selectSBTFile(item.sbtEntry());
        }
    }

    @Override
    public void cleanup() {
        thumbnailCache.cleanup();
    }

    private record CompactItem(Kind kind,
                               OMOFileManager.OMOFileEntry omoEntry,
                               SBTFileManager.SBTFileEntry sbtEntry) {
        static CompactItem omo(OMOFileManager.OMOFileEntry e) { return new CompactItem(Kind.OMO, e, null); }
        static CompactItem sbt(SBTFileManager.SBTFileEntry e) { return new CompactItem(Kind.SBT, null, e); }

        String displayName() {
            return kind == Kind.OMO ? omoEntry.name() : sbtEntry.name();
        }

        String id() {
            return kind == Kind.OMO ? omoEntry.getFilePathString() : sbtEntry.getFilePathString();
        }

        enum Kind { OMO, SBT }
    }
}
