package com.openmason.ui.components.modelBrowser.views;

import com.openmason.block.BlockManager;
import com.openmason.item.ItemManager;
import com.openmason.ui.components.modelBrowser.ModelBrowserController;
import com.openmason.ui.components.modelBrowser.ModelBrowserState;
import com.openmason.ui.components.modelBrowser.categorizers.BlockCategorizer;
import com.openmason.ui.components.modelBrowser.categorizers.ItemCategorizer;
import com.openmason.ui.components.modelBrowser.sorting.SortBy;
import com.openmason.ui.components.modelBrowser.sorting.SortOrder;
import com.openmason.ui.components.modelBrowser.thumbnails.BlockThumbnailRenderer;
import com.openmason.ui.components.modelBrowser.thumbnails.ItemThumbnailRenderer;
import com.openmason.ui.components.modelBrowser.thumbnails.ModelBrowserThumbnailCache;
import com.openmason.ui.components.modelBrowser.thumbnails.ModelThumbnailRenderer;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Grid view renderer for the Model Browser.
 *
 * <p>Displays items as a grid of thumbnails with labels, similar to
 * Windows Explorer "Large Icons" view.</p>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li><strong>Single Responsibility</strong>: Only renders grid view</li>
 *   <li><strong>Dependency Inversion</strong>: Depends on controller abstraction</li>
 * </ul>
 *
 * <p>Following KISS: Simple grid layout with straightforward rendering.</p>
 * <p>Following DRY: Reuses thumbnail renderers and helper methods.</p>
 * <p>Following YAGNI: Only implements essential features.</p>
 */
public class GridViewRenderer implements ViewRenderer {

    private static final Logger logger = LoggerFactory.getLogger(GridViewRenderer.class);

    // Grid layout constants
    private static final int THUMBNAIL_SIZE = ModelBrowserThumbnailCache.SIZE_LARGE; // 64x64
    private static final float ITEM_WIDTH = 100.0f;  // Total width including padding
    private static final float ITEM_HEIGHT = 100.0f; // Total height including label
    private static final float PADDING = 8.0f;
    private static final float LABEL_HEIGHT = 32.0f;

    private final ModelBrowserController controller;
    private final ModelBrowserThumbnailCache thumbnailCache;
    private final BlockThumbnailRenderer blockRenderer;
    private final ItemThumbnailRenderer itemRenderer;
    private final ModelThumbnailRenderer modelRenderer;

    /**
     * Creates a new grid view renderer.
     *
     * @param controller The controller managing business logic
     */
    public GridViewRenderer(ModelBrowserController controller) {
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
        List<GridItem> items = getFilteredItems(selectedCategory);

        // Apply search filter
        if (state.isSearchActive()) {
            items = filterBySearch(items, state.getSearchTextValue());
        }

        // Apply sorting (for consistency across view modes)
        items = sortItems(items, state.getSortBy(), state.getSortOrder());

        // Render grid
        if (items.isEmpty()) {
            renderEmptyState();
        } else {
            renderGrid(items);
        }
    }

    /**
     * Gets items filtered by the selected category.
     * Following YAGNI: Only implement what's needed for current categories.
     */
    private List<GridItem> getFilteredItems(String category) {
        List<GridItem> items = new ArrayList<>();

        // Parse category string
        if (category.equals("All Models")) {
            // Show everything
            addAllBlocks(items);
            addAllItems(items);
            addRecentModels(items);

        } else if (category.equals("Entity Models")) {
            // Only entity models
            addRecentModels(items);

        } else if (category.equals("Recent Files")) {
            // Only recent files
            addRecentModels(items);

        } else if (category.startsWith("Blocks > ")) {
            // Specific block category
            String blockCategory = category.substring("Blocks > ".length());
            addBlocksByCategory(items, blockCategory);

        } else if (category.startsWith("Items > ")) {
            // Specific item category
            String itemCategory = category.substring("Items > ".length());
            addItemsByCategory(items, itemCategory);
        }

        return items;
    }

    /**
     * Adds all blocks to the grid.
     */
    private void addAllBlocks(List<GridItem> items) {
        for (BlockType blockType : BlockType.values()) {
            items.add(new GridItem(
                    GridItemType.BLOCK,
                    blockType.name(),
                    BlockManager.getDisplayName(blockType),
                    blockType
            ));
        }
    }

    /**
     * Adds blocks from a specific category.
     */
    private void addBlocksByCategory(List<GridItem> items, String categoryName) {
        Map<BlockCategorizer.Category, List<BlockType>> categorized = controller.getCategorizedBlocks();

        for (BlockCategorizer.Category category : BlockCategorizer.Category.values()) {
            if (category.getDisplayName().equals(categoryName)) {
                List<BlockType> blocks = categorized.get(category);
                if (blocks != null) {
                    for (BlockType blockType : blocks) {
                        items.add(new GridItem(
                                GridItemType.BLOCK,
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
     * Adds all items to the grid.
     */
    private void addAllItems(List<GridItem> items) {
        for (ItemType itemType : ItemType.values()) {
            items.add(new GridItem(
                    GridItemType.ITEM,
                    itemType.name(),
                    ItemManager.getDisplayName(itemType),
                    itemType
            ));
        }
    }

    /**
     * Adds items from a specific category.
     */
    private void addItemsByCategory(List<GridItem> items, String categoryName) {
        Map<ItemCategorizer.Category, List<ItemType>> categorized = controller.getCategorizedItems();

        for (ItemCategorizer.Category category : ItemCategorizer.Category.values()) {
            if (category.getDisplayName().equals(categoryName)) {
                List<ItemType> itemTypes = categorized.get(category);
                if (itemTypes != null) {
                    for (ItemType itemType : itemTypes) {
                        items.add(new GridItem(
                                GridItemType.ITEM,
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
     * Adds recent model files to the grid.
     */
    private void addRecentModels(List<GridItem> items) {
        List<String> recentFiles = controller.getState().getRecentFiles();
        for (String fileName : recentFiles) {
            items.add(new GridItem(
                    GridItemType.MODEL,
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
    private List<GridItem> filterBySearch(List<GridItem> items, String searchText) {
        List<GridItem> filtered = new ArrayList<>();
        for (GridItem item : items) {
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
    private List<GridItem> sortItems(List<GridItem> items, SortBy sortBy, SortOrder sortOrder) {
        Comparator<GridItem> comparator = switch (sortBy) {
            case NAME -> Comparator.comparing(item -> item.displayName);
            case TYPE -> Comparator.comparing(item -> item.type.name());
            case CATEGORY, RECENT -> Comparator.comparing(item -> item.id); // ID preserves order
        };

        if (sortOrder == SortOrder.DESCENDING) {
            comparator = comparator.reversed();
        }

        List<GridItem> sorted = new ArrayList<>(items);
        sorted.sort(comparator);
        return sorted;
    }

    /**
     * Renders the grid of items.
     * Following KISS: Simple grid layout calculation.
     */
    private void renderGrid(List<GridItem> items) {
        ImVec2 region = ImGui.getContentRegionAvail();
        int columns = Math.max(1, (int) ((region.x + PADDING) / (ITEM_WIDTH + PADDING)));

        int column = 0;
        for (GridItem item : items) {
            // Start new row if needed
            if (column > 0) {
                ImGui.sameLine();
            }

            // Render grid item
            renderGridItem(item);

            column++;
            if (column >= columns) {
                column = 0;
            }
        }
    }

    /**
     * Renders a single grid item with thumbnail and label.
     * Following SOLID: Each item type handled separately.
     */
    private void renderGridItem(GridItem item) {
        ImGui.beginGroup();

        // Get thumbnail texture ID
        int textureId = getThumbnail(item);

        // Render thumbnail
        if (textureId > 0) {
            ImGui.image(textureId, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        } else {
            // Fallback: colored square
            ImGui.dummy(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        }

        // Handle click
        if (ImGui.isItemClicked()) {
            handleItemClick(item);
        }

        // Handle hover
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(item.displayName);
            // Subtle highlight on hover
            ImGui.getWindowDrawList().addRect(
                    ImGui.getItemRectMinX(), ImGui.getItemRectMinY(),
                    ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY(),
                    ImGui.getColorU32(ImGuiCol.HeaderHovered),
                    0.0f, 0, 2.0f
            );
        }

        // Handle right-click context menu
        if (ImGui.isItemClicked(1)) { // Right mouse button
            ImGui.openPopup("##GridItemContextMenu_" + item.id);
        }

        // Render label with fixed width to ensure consistent spacing
        float labelStartX = ImGui.getCursorPosX();
        String labelText = item.displayName;

        // Calculate text size and truncate if needed to fit within thumbnail bounds
        ImVec2 textSize = new ImVec2();
        ImGui.calcTextSize(textSize, labelText);

        if (textSize.x > THUMBNAIL_SIZE) {
            // Truncate with ellipsis to fit within thumbnail width
            while (labelText.length() > 0 && ImGui.calcTextSize(labelText + "...").x > THUMBNAIL_SIZE) {
                labelText = labelText.substring(0, labelText.length() - 1);
            }
            labelText = labelText + "...";
        }

        // Render text left-aligned within thumbnail bounds
        ImGui.text(labelText);

        // Reserve the full ITEM_WIDTH space to ensure consistent grid spacing
        ImGui.setCursorPosX(labelStartX);
        ImGui.dummy(ITEM_WIDTH, 0);

        ImGui.endGroup();

        // Context menu popup (KISS: inline implementation)
        if (ImGui.beginPopup("##GridItemContextMenu_" + item.id)) {
            renderContextMenu(item);
            ImGui.endPopup();
        }
    }

    /**
     * Gets the appropriate thumbnail for an item.
     */
    private int getThumbnail(GridItem item) {
        return switch (item.type) {
            case BLOCK -> blockRenderer.getThumbnail((BlockType) item.data, THUMBNAIL_SIZE);
            case ITEM -> itemRenderer.getThumbnail((ItemType) item.data, THUMBNAIL_SIZE);
            case MODEL -> modelRenderer.getThumbnail((String) item.data, THUMBNAIL_SIZE);
        };
    }

    /**
     * Handles click on a grid item.
     * Following SOLID: Delegate to controller for business logic.
     */
    private void handleItemClick(GridItem item) {
        switch (item.type) {
            case BLOCK -> controller.selectBlock((BlockType) item.data);
            case ITEM -> controller.selectItem((ItemType) item.data);
            case MODEL -> controller.selectModel((String) item.data);
        }
    }

    /**
     * Renders context menu for a grid item.
     * Following KISS: Simple inline menu with common actions.
     */
    private void renderContextMenu(GridItem item) {
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
    private String getThumbnailCacheKey(GridItem item) {
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
    public ViewMode getViewMode() {
        return ViewMode.GRID;
    }

    @Override
    public void cleanup() {
        thumbnailCache.cleanup();
    }

    /**
     * Simple data class for grid items.
     * Following YAGNI: Only essential fields.
     */
    private static class GridItem {
        final GridItemType type;
        final String id;
        final String displayName;
        final Object data; // BlockType, ItemType, or String (model name)

        GridItem(GridItemType type, String id, String displayName, Object data) {
            this.type = type;
            this.id = id;
            this.displayName = displayName;
            this.data = data;
        }
    }

    /**
     * Grid item types.
     */
    private enum GridItemType {
        BLOCK,
        ITEM,
        MODEL
    }
}
