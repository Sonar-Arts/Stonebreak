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
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Grid view renderer for the Model Browser — large-icon view of file-backed
 * .OMO and .SBT assets discovered on disk.
 */
public class GridViewRenderer implements ViewRenderer {

    private static final Logger logger = LoggerFactory.getLogger(GridViewRenderer.class);

    private static final int THUMBNAIL_SIZE = ModelBrowserThumbnailCache.SIZE_LARGE;
    private static final float ITEM_WIDTH = 100.0f;
    private static final float PADDING = 8.0f;

    private final ModelBrowserController controller;
    private final ModelBrowserThumbnailCache thumbnailCache;
    private final ModelThumbnailRenderer modelRenderer;
    private final SBTThumbnailRenderer sbtRenderer;

    public GridViewRenderer(ModelBrowserController controller) {
        this.controller = controller;
        this.thumbnailCache = new ModelBrowserThumbnailCache();
        this.modelRenderer = new ModelThumbnailRenderer(thumbnailCache);
        this.sbtRenderer = new SBTThumbnailRenderer(thumbnailCache);
    }

    @Override
    public void render() {
        ModelBrowserState state = controller.getState();
        List<GridItem> items = collectItems(state.getSelectedCategory());

        if (state.isSearchActive()) {
            items = filterBySearch(items);
        }
        items = sortItems(items, state.getSortBy(), state.getSortOrder());

        if (items.isEmpty()) {
            renderEmptyState();
        } else {
            renderGrid(items);
        }
    }

    private List<GridItem> collectItems(String category) {
        List<GridItem> items = new ArrayList<>();
        boolean wantOMO = category.equals("All Assets") || category.equals(".OMO Models");
        boolean wantSBT = category.equals("All Assets") || category.equals(".SBT Textures");
        boolean wantRecent = category.equals("Recent Files");

        if (wantRecent) {
            // Recent files contains names without extension; surface any matching
            // OMO/SBT entries we still have on disk.
            List<String> recent = controller.getState().getRecentFiles();
            for (String name : recent) {
                for (OMOFileManager.OMOFileEntry e : controller.getOMOFiles()) {
                    if (e.name().equals(name)) items.add(GridItem.omo(e));
                }
                for (SBTFileManager.SBTFileEntry e : controller.getSBTFiles()) {
                    if (e.name().equals(name)) items.add(GridItem.sbt(e));
                }
            }
            return items;
        }
        if (wantOMO) {
            for (OMOFileManager.OMOFileEntry e : controller.getOMOFiles()) {
                items.add(GridItem.omo(e));
            }
        }
        if (wantSBT) {
            for (SBTFileManager.SBTFileEntry e : controller.getSBTFiles()) {
                items.add(GridItem.sbt(e));
            }
        }
        return items;
    }

    private List<GridItem> filterBySearch(List<GridItem> items) {
        List<GridItem> filtered = new ArrayList<>();
        for (GridItem item : items) {
            if (controller.getState().matchesSearch(item.displayName())) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private List<GridItem> sortItems(List<GridItem> items, SortBy sortBy, SortOrder order) {
        Comparator<GridItem> comparator = switch (sortBy) {
            case TYPE -> Comparator.comparing((GridItem i) -> i.kind().ordinal())
                    .thenComparing(GridItem::displayName);
            case CATEGORY, RECENT, NAME -> Comparator.comparing(GridItem::displayName);
        };
        if (order == SortOrder.DESCENDING) comparator = comparator.reversed();
        List<GridItem> sorted = new ArrayList<>(items);
        sorted.sort(comparator);
        return sorted;
    }

    private void renderGrid(List<GridItem> items) {
        ImVec2 region = ImGui.getContentRegionAvail();
        int columns = Math.max(1, (int) ((region.x + PADDING) / (ITEM_WIDTH + PADDING)));

        int column = 0;
        for (GridItem item : items) {
            if (column > 0) ImGui.sameLine();
            renderGridItem(item);
            column++;
            if (column >= columns) column = 0;
        }
    }

    private void renderGridItem(GridItem item) {
        ImGui.beginGroup();
        int textureId = thumbnailFor(item);
        if (textureId > 0) {
            ImGui.image(textureId, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        } else {
            ImGui.dummy(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        }

        if (ImGui.isItemClicked()) {
            handleClick(item);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(item.displayName() + "\n" + item.kind().label());
            ImGui.getWindowDrawList().addRect(
                    ImGui.getItemRectMinX(), ImGui.getItemRectMinY(),
                    ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY(),
                    ImGui.getColorU32(ImGuiCol.HeaderHovered),
                    0.0f, 0, 2.0f
            );
        }
        if (ImGui.isItemClicked(1)) {
            ImGui.openPopup("##GridItemContextMenu_" + item.id());
        }

        float labelStartX = ImGui.getCursorPosX();
        String labelText = item.displayName();
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

        if (ImGui.beginPopup("##GridItemContextMenu_" + item.id())) {
            renderContextMenu(item);
            ImGui.endPopup();
        }
    }

    private int thumbnailFor(GridItem item) {
        return switch (item.kind()) {
            case OMO -> modelRenderer.getThumbnail(item.omoEntry(), THUMBNAIL_SIZE);
            case SBT -> sbtRenderer.getThumbnail(item.sbtEntry(), THUMBNAIL_SIZE);
        };
    }

    private void handleClick(GridItem item) {
        switch (item.kind()) {
            case OMO -> controller.selectOMOFile(item.omoEntry());
            case SBT -> controller.selectSBTFile(item.sbtEntry());
        }
    }

    private void renderContextMenu(GridItem item) {
        ImGui.text(item.displayName());
        ImGui.separator();
        if (ImGui.menuItem("Select")) {
            handleClick(item);
            ImGui.closeCurrentPopup();
        }
        if (ImGui.menuItem("Copy Name")) {
            ImGui.setClipboardText(item.displayName());
            ImGui.closeCurrentPopup();
        }
        if (ImGui.menuItem("Copy Path")) {
            ImGui.setClipboardText(item.id());
            ImGui.closeCurrentPopup();
        }
        ImGui.separator();
        if (ImGui.menuItem("Refresh Thumbnail")) {
            String key = item.kind() == GridItem.Kind.OMO
                    ? ModelBrowserThumbnailCache.omoKey(item.id(), THUMBNAIL_SIZE)
                    : ModelBrowserThumbnailCache.sbtKey(item.id(), THUMBNAIL_SIZE);
            thumbnailCache.invalidate(key);
            ImGui.closeCurrentPopup();
        }
    }

    private void renderEmptyState() {
        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();
        float w = ImGui.getContentRegionAvailX();
        String msg = "No assets in this folder";
        ImGui.setCursorPosX((w - ImGui.calcTextSize(msg).x) * 0.5f);
        ImGui.textDisabled(msg);
        ImGui.spacing();
        String hint = controller.getState().isSearchActive()
                ? "Try clearing your search."
                : "Drop .OMO or .SBT files into the configured folders, then click Refresh.";
        ImGui.setCursorPosX((w - ImGui.calcTextSize(hint).x) * 0.5f);
        ImGui.text(hint);
    }

    @Override
    public void cleanup() {
        thumbnailCache.cleanup();
    }

    /** Item shown in the grid — either an .OMO model or .SBT texture. */
    private record GridItem(Kind kind,
                            OMOFileManager.OMOFileEntry omoEntry,
                            SBTFileManager.SBTFileEntry sbtEntry) {

        static GridItem omo(OMOFileManager.OMOFileEntry e) { return new GridItem(Kind.OMO, e, null); }
        static GridItem sbt(SBTFileManager.SBTFileEntry e) { return new GridItem(Kind.SBT, null, e); }

        String displayName() {
            return kind == Kind.OMO ? omoEntry.name() : sbtEntry.name();
        }

        String id() {
            return kind == Kind.OMO ? omoEntry.getFilePathString() : sbtEntry.getFilePathString();
        }

        enum Kind {
            OMO(".OMO Model"),
            SBT(".SBT Texture");

            private final String label;
            Kind(String label) { this.label = label; }
            String label() { return label; }
        }
    }
}
