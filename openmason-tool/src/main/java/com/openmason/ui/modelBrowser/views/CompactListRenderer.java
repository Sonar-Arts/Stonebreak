package com.openmason.ui.modelBrowser.views;

import com.openmason.block.BlockManager;
import com.openmason.item.ItemManager;
import com.openmason.ui.modelBrowser.ModelBrowserController;
import com.openmason.ui.modelBrowser.ModelBrowserState;
import com.openmason.ui.modelBrowser.categorizers.BlockCategorizer;
import com.openmason.ui.modelBrowser.categorizers.ItemCategorizer;
import com.openmason.ui.modelBrowser.sorting.SortBy;
import com.openmason.ui.modelBrowser.sorting.SortOrder;
import com.openmason.ui.modelBrowser.thumbnails.BlockThumbnailRenderer;
import com.openmason.ui.modelBrowser.thumbnails.ItemThumbnailRenderer;
import com.openmason.ui.modelBrowser.thumbnails.ModelBrowserThumbnailCache;
import com.openmason.ui.modelBrowser.thumbnails.ModelThumbnailRenderer;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import imgui.ImGui;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Compact list view renderer for the Model Browser.
 */
public class CompactListRenderer implements ViewRenderer {

    private static final Logger logger = LoggerFactory.getLogger(CompactListRenderer.class);

    // Compact layout constants
    private static final int THUMBNAIL_SIZE = ModelBrowserThumbnailCache.SIZE_SMALL; // 16x16
    private static final float ITEM_SPACING = 2.0f; // Tight spacing for compact view
    private static final float ICON_TEXT_SPACING = 4.0f;

    private final ModelBrowserController controller;
    private final ModelBrowserThumbnailCache thumbnailCache;
    private final BlockThumbnailRenderer blockRenderer;
    private final ItemThumbnailRenderer itemRenderer;
    private final ModelThumbnailRenderer modelRenderer;

    /**
     * Creates a new compact list view renderer.
     *
     * @param controller The controller managing business logic
     */
    public CompactListRenderer(ModelBrowserController controller) {
        this.controller = controller;
        this.thumbnailCache = new ModelBrowserThumbnailCache();
        this.blockRenderer = new BlockThumbnailRenderer(thumbnailCache);
        this.itemRenderer = new ItemThumbnailRenderer(thumbnailCache);
        this.modelRenderer = new ModelThumbnailRenderer(thumbnailCache);
    }

    @Override
    public void render() {
        ModelBrowserState state = controller.getState();
        String selectedCategory = state.getSelectedCategory();

        // Get filtered items based on selected category
        List<CompactItem> items = getFilteredItems(selectedCategory);

        // Apply search filter
        if (state.isSearchActive()) {
            items = filterBySearch(items, state.getSearchTextValue());
        }

        // Apply sorting (for consistency across view modes)
        items = sortItems(items, state.getSortBy(), state.getSortOrder());

        // Render compact list
        if (items.isEmpty()) {
            renderEmptyState();
        } else {
            renderCompactList(items);
        }
    }

    /**
     * Gets items filtered by the selected category.
     * Following DRY: Reuses logic from GridViewRenderer and ListViewRenderer.
     */
    private List<CompactItem> getFilteredItems(String category) {
        List<CompactItem> items = new ArrayList<>();

        // Parse category string
        if (category.equals("All Models")) {
            addAllBlocks(items);
            addAllItems(items);
            addRecentModels(items);

        } else if (category.equals("Entity Models")) {
            addRecentModels(items);

        } else if (category.equals("Recent Files")) {
            addRecentModels(items);

        } else if (category.startsWith("Blocks > ")) {
            String blockCategory = category.substring("Blocks > ".length());
            addBlocksByCategory(items, blockCategory);

        } else if (category.startsWith("Items > ")) {
            String itemCategory = category.substring("Items > ".length());
            addItemsByCategory(items, itemCategory);
        }

        return items;
    }

    /**
     * Adds all blocks to the list.
     */
    private void addAllBlocks(List<CompactItem> items) {
        for (BlockType blockType : BlockType.values()) {
            items.add(new CompactItem(
                    CompactItemType.BLOCK,
                    blockType.name(),
                    BlockManager.getDisplayName(blockType),
                    blockType
            ));
        }
    }

    /**
     * Adds blocks from a specific category.
     */
    private void addBlocksByCategory(List<CompactItem> items, String categoryName) {
        Map<BlockCategorizer.Category, List<BlockType>> categorized = controller.getCategorizedBlocks();

        for (BlockCategorizer.Category category : BlockCategorizer.Category.values()) {
            if (category.getDisplayName().equals(categoryName)) {
                List<BlockType> blocks = categorized.get(category);
                if (blocks != null) {
                    for (BlockType blockType : blocks) {
                        items.add(new CompactItem(
                                CompactItemType.BLOCK,
                                blockType.name(),
                                BlockManager.getDisplayName(blockType),
                                blockType
                        ));
                    }
                }
                break;
            }
        }
    }

    /**
     * Adds all items to the list.
     */
    private void addAllItems(List<CompactItem> items) {
        for (ItemType itemType : ItemType.values()) {
            items.add(new CompactItem(
                    CompactItemType.ITEM,
                    itemType.name(),
                    ItemManager.getDisplayName(itemType),
                    itemType
            ));
        }
    }

    /**
     * Adds items from a specific category.
     */
    private void addItemsByCategory(List<CompactItem> items, String categoryName) {
        Map<ItemCategorizer.Category, List<ItemType>> categorized = controller.getCategorizedItems();

        for (ItemCategorizer.Category category : ItemCategorizer.Category.values()) {
            if (category.getDisplayName().equals(categoryName)) {
                List<ItemType> itemTypes = categorized.get(category);
                if (itemTypes != null) {
                    for (ItemType itemType : itemTypes) {
                        items.add(new CompactItem(
                                CompactItemType.ITEM,
                                itemType.name(),
                                ItemManager.getDisplayName(itemType),
                                itemType
                        ));
                    }
                }
                break;
            }
        }
    }

    /**
     * Adds recent model files to the list.
     */
    private void addRecentModels(List<CompactItem> items) {
        List<String> recentFiles = controller.getState().getRecentFiles();
        for (String fileName : recentFiles) {
            items.add(new CompactItem(
                    CompactItemType.MODEL,
                    fileName,
                    fileName,
                    fileName
            ));
        }
    }

    /**
     * Filters items by search text.
     * Following DRY: Reuse state.matchesSearch logic.
     */
    private List<CompactItem> filterBySearch(List<CompactItem> items, String searchText) {
        List<CompactItem> filtered = new ArrayList<>();
        for (CompactItem item : items) {
            if (controller.getState().matchesSearch(item.displayName)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    /**
     * Sorts items based on sort criteria.
     * Following KISS: Simple comparator-based sorting.
     */
    private List<CompactItem> sortItems(List<CompactItem> items, SortBy sortBy, SortOrder sortOrder) {
        Comparator<CompactItem> comparator = switch (sortBy) {
            case NAME -> Comparator.comparing(item -> item.displayName);
            case TYPE -> Comparator.comparing(item -> item.type.name());
            case CATEGORY, RECENT -> Comparator.comparing(item -> item.id); // ID preserves order
        };

        if (sortOrder == SortOrder.DESCENDING) {
            comparator = comparator.reversed();
        }

        List<CompactItem> sorted = new ArrayList<>(items);
        sorted.sort(comparator);
        return sorted;
    }

    /**
     * Renders the compact list with tight spacing.
     * Following KISS: Simple vertical list with icon + text.
     */
    private void renderCompactList(List<CompactItem> items) {
        // Apply tight spacing for compact view
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, ITEM_SPACING);

        for (CompactItem item : items) {
            renderCompactItem(item);
        }

        ImGui.popStyleVar();
    }

    /**
     * Renders a single compact list item with small icon and name.
     * Following KISS: Minimal rendering - just icon + text on same line.
     */
    private void renderCompactItem(CompactItem item) {
        // Get thumbnail texture ID
        int textureId = getThumbnail(item);

        // Render small thumbnail
        if (textureId > 0) {
            ImGui.image(textureId, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        } else {
            // Fallback: small colored square
            ImGui.dummy(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        }

        // Same line for text
        ImGui.sameLine(0, ICON_TEXT_SPACING);

        // Render selectable text
        if (ImGui.selectable(item.displayName, false)) {
            handleItemClick(item);
        }

        // Handle right-click context menu
        if (ImGui.isItemClicked(1)) { // Right mouse button
            ImGui.openPopup("##CompactItemContextMenu_" + item.id);
        }

        // Tooltip on hover
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(item.displayName);
        }

        // Context menu popup (KISS: inline implementation)
        if (ImGui.beginPopup("##CompactItemContextMenu_" + item.id)) {
            renderContextMenu(item);
            ImGui.endPopup();
        }
    }

    /**
     * Gets the appropriate thumbnail for an item.
     * Following DRY: Reuses thumbnail renderers.
     */
    private int getThumbnail(CompactItem item) {
        return switch (item.type) {
            case BLOCK -> blockRenderer.getThumbnail((BlockType) item.data, THUMBNAIL_SIZE);
            case ITEM -> itemRenderer.getThumbnail((ItemType) item.data, THUMBNAIL_SIZE);
            case MODEL -> modelRenderer.getThumbnail((String) item.data, THUMBNAIL_SIZE);
        };
    }

    /**
     * Handles click on a compact list item.
     * Following SOLID: Delegate to controller for business logic.
     */
    private void handleItemClick(CompactItem item) {
        switch (item.type) {
            case BLOCK -> controller.selectBlock((BlockType) item.data);
            case ITEM -> controller.selectItem((ItemType) item.data);
            case MODEL -> controller.selectModel((String) item.data);
        }
    }

    /**
     * Renders context menu for a compact list item.
     * Following KISS: Simple inline menu with common actions.
     */
    private void renderContextMenu(CompactItem item) {
        ImGui.text(item.displayName);
        ImGui.separator();

        // Select action
        if (ImGui.menuItem("Select")) {
            handleItemClick(item);
            ImGui.closeCurrentPopup();
        }

        // Copy name action
        if (ImGui.menuItem("Copy Name")) {
            ImGui.setClipboardText(item.displayName);
            ImGui.closeCurrentPopup();
        }

        // Copy ID action
        if (ImGui.menuItem("Copy ID")) {
            ImGui.setClipboardText(item.id);
            ImGui.closeCurrentPopup();
        }

        ImGui.separator();

        // Refresh thumbnail action
        if (ImGui.menuItem("Refresh Thumbnail")) {
            thumbnailCache.invalidate(getThumbnailCacheKey(item));
            ImGui.closeCurrentPopup();
        }

        // Type-specific actions
        switch (item.type) {
            case BLOCK -> {
                ImGui.separator();
                if (ImGui.menuItem("View Block Properties")) {
                    logger.info("Block properties: {}", item.data);
                    ImGui.closeCurrentPopup();
                }
            }
            case ITEM -> {
                ImGui.separator();
                if (ImGui.menuItem("View Item Properties")) {
                    logger.info("Item properties: {}", item.data);
                    ImGui.closeCurrentPopup();
                }
            }
            case MODEL -> {
                ImGui.separator();
                if (ImGui.menuItem("View Model Properties")) {
                    logger.info("Model properties: {}", item.data);
                    ImGui.closeCurrentPopup();
                }
            }
        }
    }

    /**
     * Gets the thumbnail cache key for an item.
     * Following DRY: Centralize cache key generation.
     */
    private String getThumbnailCacheKey(CompactItem item) {
        return switch (item.type) {
            case BLOCK -> ModelBrowserThumbnailCache.blockKey(item.id, THUMBNAIL_SIZE);
            case ITEM -> ModelBrowserThumbnailCache.itemKey(item.id, THUMBNAIL_SIZE);
            case MODEL -> ModelBrowserThumbnailCache.modelKey(item.id, THUMBNAIL_SIZE);
        };
    }

    /**
     * Renders empty state when no items to show.
     * Following UX best practices: Helpful, actionable empty states.
     */
    private void renderEmptyState() {
        // Center the empty state message
        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();

        float windowWidth = ImGui.getContentRegionAvailX();
        float textWidth = ImGui.calcTextSize("No items found").x;
        ImGui.setCursorPosX((windowWidth - textWidth) * 0.5f);

        ImGui.textDisabled("No items found");
        ImGui.spacing();

        if (controller.getState().isSearchActive()) {
            String suggestion = "Try adjusting your search or clearing filters";
            float suggestionWidth = ImGui.calcTextSize(suggestion).x;
            ImGui.setCursorPosX((windowWidth - suggestionWidth) * 0.5f);
            ImGui.text(suggestion);
        } else {
            String suggestion = "Select a category from the sidebar";
            float suggestionWidth = ImGui.calcTextSize(suggestion).x;
            ImGui.setCursorPosX((windowWidth - suggestionWidth) * 0.5f);
            ImGui.text(suggestion);
        }
    }

    @Override
    public void cleanup() {
        thumbnailCache.cleanup();
    }

    /**
     * Simple data class for compact list items.
     */
        private record CompactItem(CompactItemType type, String id, String displayName, Object data) {
    }

    /**
     * Compact item types.
     */
    private enum CompactItemType {
        BLOCK,
        ITEM,
        MODEL
    }
}
