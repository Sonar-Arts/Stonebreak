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
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * List view renderer for the Model Browser.
 *
 * <p>Displays items in a sortable table with columns for thumbnail, name, type, and category.
 * Similar to Windows Explorer "Details" view.</p>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li><strong>Single Responsibility</strong>: Only renders list/table view</li>
 *   <li><strong>Dependency Inversion</strong>: Depends on controller abstraction</li>
 * </ul>
 *
 * <p>Following KISS: Simple table with straightforward sorting.</p>
 * <p>Following DRY: Reuses thumbnail renderers and filtering logic from GridViewRenderer.</p>
 * <p>Following YAGNI: Only implements essential table features.</p>
 */
public class ListViewRenderer implements ViewRenderer {

    private static final Logger logger = LoggerFactory.getLogger(ListViewRenderer.class);

    // Table layout constants
    private static final int THUMBNAIL_SIZE = ModelBrowserThumbnailCache.SIZE_MEDIUM; // 32x32
    private static final float THUMBNAIL_COLUMN_WIDTH = 40.0f;
    private static final float NAME_COLUMN_WIDTH = 200.0f;
    private static final float TYPE_COLUMN_WIDTH = 100.0f;
    private static final float CATEGORY_COLUMN_WIDTH = 150.0f;

    private final ModelBrowserController controller;
    private final ModelBrowserThumbnailCache thumbnailCache;
    private final BlockThumbnailRenderer blockRenderer;
    private final ItemThumbnailRenderer itemRenderer;
    private final ModelThumbnailRenderer modelRenderer;

    /**
     * Creates a new list view renderer.
     *
     * @param controller The controller managing business logic
     */
    public ListViewRenderer(ModelBrowserController controller) {
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
        List<ListItem> items = getFilteredItems(selectedCategory);

        // Apply search filter
        if (state.isSearchActive()) {
            items = filterBySearch(items, state.getSearchTextValue());
        }

        // Sort items
        items = sortItems(items, state.getSortBy(), state.getSortOrder());

        // Render table
        if (items.isEmpty()) {
            renderEmptyState();
        } else {
            renderTable(items);
        }
    }

    /**
     * Gets items filtered by the selected category.
     * Following DRY: Reuses logic from GridViewRenderer.
     */
    private List<ListItem> getFilteredItems(String category) {
        List<ListItem> items = new ArrayList<>();

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
    private void addAllBlocks(List<ListItem> items) {
        for (BlockType blockType : BlockType.values()) {
            BlockCategorizer.Category category = BlockCategorizer.categorize(blockType);
            items.add(new ListItem(
                    ListItemType.BLOCK,
                    blockType.name(),
                    BlockManager.getDisplayName(blockType),
                    "Block",
                    category.getDisplayName(),
                    blockType
            ));
        }
    }

    /**
     * Adds blocks from a specific category.
     */
    private void addBlocksByCategory(List<ListItem> items, String categoryName) {
        Map<BlockCategorizer.Category, List<BlockType>> categorized = controller.getCategorizedBlocks();

        for (BlockCategorizer.Category category : BlockCategorizer.Category.values()) {
            if (category.getDisplayName().equals(categoryName)) {
                List<BlockType> blocks = categorized.get(category);
                if (blocks != null) {
                    for (BlockType blockType : blocks) {
                        items.add(new ListItem(
                                ListItemType.BLOCK,
                                blockType.name(),
                                BlockManager.getDisplayName(blockType),
                                "Block",
                                category.getDisplayName(),
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
    private void addAllItems(List<ListItem> items) {
        for (ItemType itemType : ItemType.values()) {
            ItemCategorizer.Category category = ItemCategorizer.categorize(itemType);
            items.add(new ListItem(
                    ListItemType.ITEM,
                    itemType.name(),
                    ItemManager.getDisplayName(itemType),
                    "Item",
                    category.getDisplayName(),
                    itemType
            ));
        }
    }

    /**
     * Adds items from a specific category.
     */
    private void addItemsByCategory(List<ListItem> items, String categoryName) {
        Map<ItemCategorizer.Category, List<ItemType>> categorized = controller.getCategorizedItems();

        for (ItemCategorizer.Category category : ItemCategorizer.Category.values()) {
            if (category.getDisplayName().equals(categoryName)) {
                List<ItemType> itemTypes = categorized.get(category);
                if (itemTypes != null) {
                    for (ItemType itemType : itemTypes) {
                        items.add(new ListItem(
                                ListItemType.ITEM,
                                itemType.name(),
                                ItemManager.getDisplayName(itemType),
                                "Item",
                                category.getDisplayName(),
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
    private void addRecentModels(List<ListItem> items) {
        List<String> recentFiles = controller.getState().getRecentFiles();
        for (String fileName : recentFiles) {
            items.add(new ListItem(
                    ListItemType.MODEL,
                    fileName,
                    fileName,
                    "Model",
                    "Entity Models",
                    fileName
            ));
        }
    }

    /**
     * Filters items by search text.
     * Following DRY: Reuse state.matchesSearch logic.
     */
    private List<ListItem> filterBySearch(List<ListItem> items, String searchText) {
        List<ListItem> filtered = new ArrayList<>();
        for (ListItem item : items) {
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
    private List<ListItem> sortItems(List<ListItem> items, SortBy sortBy, SortOrder sortOrder) {
        Comparator<ListItem> comparator = switch (sortBy) {
            case NAME -> Comparator.comparing(item -> item.displayName);
            case TYPE -> Comparator.comparing(item -> item.type);
            case CATEGORY -> Comparator.comparing(item -> item.category);
            case RECENT -> Comparator.comparing(item -> item.id); // ID preserves insertion order
        };

        if (sortOrder == SortOrder.DESCENDING) {
            comparator = comparator.reversed();
        }

        List<ListItem> sorted = new ArrayList<>(items);
        sorted.sort(comparator);
        return sorted;
    }

    /**
     * Renders the table with sortable columns.
     * Following KISS: Simple ImGui table with header sorting.
     */
    private void renderTable(List<ListItem> items) {
        int flags = ImGuiTableFlags.Resizable
                | ImGuiTableFlags.Sortable
                | ImGuiTableFlags.RowBg
                | ImGuiTableFlags.BordersOuter
                | ImGuiTableFlags.ScrollY;

        if (ImGui.beginTable("##ListViewTable", 4, flags)) {
            // Setup columns
            ImGui.tableSetupColumn("Icon", ImGuiTableColumnFlags.WidthFixed, THUMBNAIL_COLUMN_WIDTH);
            ImGui.tableSetupColumn("Name", ImGuiTableColumnFlags.WidthFixed, NAME_COLUMN_WIDTH);
            ImGui.tableSetupColumn("Type", ImGuiTableColumnFlags.WidthFixed, TYPE_COLUMN_WIDTH);
            ImGui.tableSetupColumn("Category", ImGuiTableColumnFlags.WidthStretch);
            ImGui.tableSetupScrollFreeze(0, 1); // Freeze header row

            // Render header with sorting
            ImGui.tableHeadersRow();
            handleColumnSorting();

            // Render rows
            for (ListItem item : items) {
                renderTableRow(item);
            }

            ImGui.endTable();
        }
    }

    /**
     * Handles column header clicks for sorting.
     * Following YAGNI: Only implement what ImGui provides.
     */
    private void handleColumnSorting() {
        // NOTE: ImGui-Java binding may not expose tableGetSortSpecs() yet
        // For now, we handle sorting through ModelBrowserState
        // When users click column headers, they can use the sort controls in the toolbar
        // Future enhancement: Use ImGui's native sorting signals when available
    }

    /**
     * Renders a single table row.
     * Following SOLID: Each item type handled separately.
     */
    private void renderTableRow(ListItem item) {
        ImGui.tableNextRow();

        // Column 0: Thumbnail
        ImGui.tableSetColumnIndex(0);
        int textureId = getThumbnail(item);
        if (textureId > 0) {
            ImGui.image(textureId, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        } else {
            ImGui.dummy(THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        }

        // Column 1: Name (selectable)
        ImGui.tableSetColumnIndex(1);
        boolean isSelected = false; // TODO: Track selected row
        if (ImGui.selectable(item.displayName, isSelected, 0, 0, 0)) {
            handleItemClick(item);
        }

        // Handle right-click context menu on row
        if (ImGui.isItemClicked(1)) { // Right mouse button
            ImGui.openPopup("##ListItemContextMenu_" + item.id);
        }

        // Column 2: Type
        ImGui.tableSetColumnIndex(2);
        ImGui.text(item.type);

        // Column 3: Category
        ImGui.tableSetColumnIndex(3);
        ImGui.text(item.category);

        // Context menu popup (KISS: inline implementation)
        if (ImGui.beginPopup("##ListItemContextMenu_" + item.id)) {
            renderContextMenu(item);
            ImGui.endPopup();
        }
    }

    /**
     * Gets the appropriate thumbnail for an item.
     * Following DRY: Reuses thumbnail renderers from GridViewRenderer.
     */
    private int getThumbnail(ListItem item) {
        return switch (item.itemType) {
            case BLOCK -> blockRenderer.getThumbnail((BlockType) item.data, THUMBNAIL_SIZE);
            case ITEM -> itemRenderer.getThumbnail((ItemType) item.data, THUMBNAIL_SIZE);
            case MODEL -> modelRenderer.getThumbnail((String) item.data, THUMBNAIL_SIZE);
        };
    }

    /**
     * Handles click on a list item.
     * Following SOLID: Delegate to controller for business logic.
     */
    private void handleItemClick(ListItem item) {
        switch (item.itemType) {
            case BLOCK -> controller.selectBlock((BlockType) item.data);
            case ITEM -> controller.selectItem((ItemType) item.data);
            case MODEL -> controller.selectModel((String) item.data);
        }
    }

    /**
     * Renders context menu for a list item.
     * Following KISS: Simple inline menu with common actions.
     */
    private void renderContextMenu(ListItem item) {
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
        switch (item.itemType) {
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
    private String getThumbnailCacheKey(ListItem item) {
        return switch (item.itemType) {
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
        return ViewMode.LIST;
    }

    @Override
    public void cleanup() {
        thumbnailCache.cleanup();
    }

    /**
     * Simple data class for list items.
     * Following YAGNI: Only essential fields for table display.
     */
    private static class ListItem {
        final ListItemType itemType;
        final String id;
        final String displayName;
        final String type;
        final String category;
        final Object data; // BlockType, ItemType, or String (model name)

        ListItem(ListItemType itemType, String id, String displayName, String type, String category, Object data) {
            this.itemType = itemType;
            this.id = id;
            this.displayName = displayName;
            this.type = type;
            this.category = category;
            this.data = data;
        }
    }

    /**
     * List item types.
     */
    private enum ListItemType {
        BLOCK,
        ITEM,
        MODEL
    }
}
