package com.openmason.ui.components.modelBrowser;

import com.openmason.block.BlockManager;
import com.openmason.item.ItemManager;
import com.openmason.ui.components.modelBrowser.categorizers.BlockCategorizer;
import com.openmason.ui.components.modelBrowser.categorizers.ItemCategorizer;
import com.openmason.ui.components.modelBrowser.components.BreadcrumbNavigator;
import com.openmason.ui.components.modelBrowser.components.SidebarRenderer;
import com.openmason.ui.components.modelBrowser.filters.FilterType;
import com.openmason.ui.components.modelBrowser.sorting.SortBy;
import com.openmason.ui.components.modelBrowser.sorting.SortOrder;
import com.openmason.ui.components.modelBrowser.views.CompactListRenderer;
import com.openmason.ui.components.modelBrowser.views.GridViewRenderer;
import com.openmason.ui.components.modelBrowser.views.ListViewRenderer;
import com.openmason.ui.components.modelBrowser.views.ViewMode;
import com.openmason.ui.components.modelBrowser.views.ViewRenderer;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Model Browser UI component using Dear ImGui.
 *
 * <p>This class follows the Single Responsibility Principle by focusing solely on
 * rendering the model browser UI. All business logic is delegated to the
 * ModelBrowserController.</p>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li>Single Responsibility: Only handles UI rendering</li>
 *   <li>Dependency Inversion: Depends on controller abstraction</li>
 * </ul>
 *
 * <p>Following KISS: Simple rendering logic, no complex state management.</p>
 * <p>Following DRY: Reuses rendering methods for similar UI elements.</p>
 */
public class ModelBrowserImGui {

    private static final Logger logger = LoggerFactory.getLogger(ModelBrowserImGui.class);

    // UI spacing and styling constants
    private static final float SECTION_SPACING = 8.0f;
    private static final float CONTROL_SPACING = 4.0f;
    private static final float SEARCH_BAR_WIDTH = 220.0f;
    private static final float FILTER_WIDTH = 150.0f;
    private static final float INDENT_SPACING = 16.0f;
    private static final float SPLITTER_WIDTH = 4.0f;
    private static final float TOOLBAR_HEIGHT = 60.0f;

    private final ModelBrowserController controller;
    private final ImBoolean visible;

    // New components for file explorer layout
    private final SidebarRenderer sidebarRenderer;
    private final BreadcrumbNavigator breadcrumbNavigator;
    private final ViewRenderer gridViewRenderer;
    private final ViewRenderer listViewRenderer;
    private final ViewRenderer compactListRenderer;

    /**
     * Creates a new Model Browser UI component.
     *
     * @param controller The controller managing business logic
     * @param visible The visibility state (shared with UIVisibilityState)
     */
    public ModelBrowserImGui(ModelBrowserController controller, ImBoolean visible) {
        this.controller = controller;
        this.visible = visible;

        // Initialize new components
        this.sidebarRenderer = new SidebarRenderer(controller);
        this.breadcrumbNavigator = new BreadcrumbNavigator(controller);
        this.gridViewRenderer = new GridViewRenderer(controller);
        this.listViewRenderer = new ListViewRenderer(controller);
        this.compactListRenderer = new CompactListRenderer(controller);

        logger.debug("ModelBrowserImGui initialized with file explorer layout and all three view modes");
    }

    /**
     * Renders the Model Browser window with file explorer layout.
     * Should be called every frame when the window should be visible.
     */
    public void render() {
        if (!visible.get()) {
            return;
        }

        // Set default window size on first appearance
        ImGui.setNextWindowSize(900, 600, imgui.flag.ImGuiCond.FirstUseEver);
        ImGui.setNextWindowPos(100, 100, imgui.flag.ImGuiCond.FirstUseEver);

        if (ImGui.begin("Model Browser", visible, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            // Toolbar area: Search, Filter, View controls
            renderToolbar();

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Breadcrumb navigation
            breadcrumbNavigator.render();

            ImGui.spacing();

            // Two-panel layout: Sidebar + Content Area
            renderTwoPanelLayout();

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Status footer
            renderStatusFooter();
        }
        ImGui.end();
    }


    /**
     * Renders the toolbar with search, filter, view, and sort controls.
     * Following KISS: Simple inline controls, no over-engineering.
     */
    private void renderToolbar() {
        renderSearchBar();
        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();
        renderFilterCombo();
        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();
        renderViewModeToggle();

        // Only show sort controls in List view (where they're useful)
        if (controller.getState().getViewMode() == ViewMode.LIST) {
            ImGui.sameLine();
            ImGui.spacing();
            ImGui.sameLine();
            renderSortControls();
        }

        // Help button (far right)
        ImGui.sameLine();
        float availWidth = ImGui.getContentRegionAvailX();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + availWidth - 25);
        if (ImGui.smallButton("?")) {
            ImGui.openPopup("##HelpPopup");
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Help & Tips");
        }

        // Help popup
        if (ImGui.beginPopup("##HelpPopup")) {
            ImGui.text("Model Browser Help");
            ImGui.separator();
            ImGui.spacing();
            ImGui.text("View Modes:");
            ImGui.bulletText("Grid - Large thumbnails");
            ImGui.bulletText("List - Sortable table");
            ImGui.bulletText("Compact - Dense list");
            ImGui.spacing();
            ImGui.text("Features:");
            ImGui.bulletText("Right-click items for menu");
            ImGui.bulletText("Search filters all items");
            ImGui.bulletText("Drag splitter to resize");
            ImGui.endPopup();
        }
    }

    /**
     * Renders a view mode dropdown combo.
     */
    private void renderViewModeToggle() {
        ModelBrowserState state = controller.getState();
        ViewMode currentMode = state.getViewMode();

        ImGui.alignTextToFramePadding();
        ImGui.text("View:");
        ImGui.sameLine();

        // View mode dropdown
        ImGui.pushItemWidth(100.0f);
        String[] viewOptions = {"Grid", "List", "Compact"};
        int currentViewIndex = switch (currentMode) {
            case GRID -> 0;
            case LIST -> 1;
            case COMPACT -> 2;
        };

        ImInt viewIndex = new ImInt(currentViewIndex);
        if (ImGui.combo("##viewMode", viewIndex, viewOptions)) {
            ViewMode newMode = switch (viewIndex.get()) {
                case 1 -> ViewMode.LIST;
                case 2 -> ViewMode.COMPACT;
                default -> ViewMode.GRID;
            };
            state.setViewMode(newMode);
        }
        ImGui.popItemWidth();
    }

    /**
     * Renders simple sort controls (KISS implementation).
     * Only shown in List view where sorting is most useful.
     */
    private void renderSortControls() {
        ModelBrowserState state = controller.getState();

        ImGui.alignTextToFramePadding();
        ImGui.text("Sort:");
        ImGui.sameLine();

        // Sort field combo
        ImGui.pushItemWidth(100.0f);
        String[] sortOptions = {"Name", "Type", "Category"};
        int currentSortIndex = switch (state.getSortBy()) {
            case NAME -> 0;
            case TYPE -> 1;
            case CATEGORY -> 2;
            case RECENT -> 0; // Default to Name
        };

        ImInt sortIndex = new ImInt(currentSortIndex);
        if (ImGui.combo("##sortBy", sortIndex, sortOptions)) {
            SortBy newSort = switch (sortIndex.get()) {
                case 1 -> SortBy.TYPE;
                case 2 -> SortBy.CATEGORY;
                default -> SortBy.NAME;
            };
            state.setSortBy(newSort);
        }
        ImGui.popItemWidth();

        // Sort order toggle button
        ImGui.sameLine();
        String orderIcon = state.getSortOrder() == SortOrder.ASCENDING ? "\u2191" : "\u2193"; // Up/Down arrows
        if (ImGui.smallButton(orderIcon)) {
            state.setSortOrder(state.getSortOrder().toggle());
        }

        // Tooltip
        if (ImGui.isItemHovered()) {
            String orderText = state.getSortOrder() == SortOrder.ASCENDING ? "Ascending" : "Descending";
            ImGui.setTooltip("Sort order: " + orderText + "\nClick to toggle");
        }
    }

    /**
     * Renders the two-panel layout with resizable splitter.
     */
    private void renderTwoPanelLayout() {
        ModelBrowserState state = controller.getState();
        ImVec2 region = ImGui.getContentRegionAvail();

        float sidebarWidth = state.getSidebarWidth();
        float contentWidth = region.x - sidebarWidth - SPLITTER_WIDTH;

        // Left panel: Sidebar
        if (ImGui.beginChild("##Sidebar", sidebarWidth, region.y, true)) {
            sidebarRenderer.render();
        }
        ImGui.endChild();

        ImGui.sameLine();

        // Resizable splitter
        renderSplitter(sidebarWidth, region.y);

        ImGui.sameLine();

        // Right panel: Content area (no scrollbar - views handle their own scrolling)
        if (ImGui.beginChild("##ContentArea", contentWidth, region.y, true, ImGuiWindowFlags.NoScrollbar)) {
            // Render the active view based on view mode
            renderActiveView(state.getViewMode());
        }
        ImGui.endChild();
    }

    /**
     * Renders the appropriate view based on the current view mode.
     * Following Strategy pattern: Delegates to appropriate view renderer.
     *
     * @param viewMode The current view mode
     */
    private void renderActiveView(ViewMode viewMode) {
        switch (viewMode) {
            case GRID -> gridViewRenderer.render();
            case LIST -> listViewRenderer.render();
            case COMPACT -> compactListRenderer.render();
        }
    }

    /**
     * Renders a draggable splitter between sidebar and content area.
     *
     * @param currentWidth Current sidebar width
     * @param height Height of the splitter
     */
    private void renderSplitter(float currentWidth, float height) {
        ImGui.button("##Splitter", SPLITTER_WIDTH, height);

        if (ImGui.isItemHovered()) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
        }

        if (ImGui.isItemActive()) {
            float delta = ImGui.getIO().getMouseDeltaX();
            if (delta != 0) {
                controller.getState().setSidebarWidth(currentWidth + delta);
            }
        }
    }

    /**
     * Renders the search bar with modern styling and clear button.
     */
    private void renderSearchBar() {
        ModelBrowserState state = controller.getState();

        ImGui.alignTextToFramePadding();
        ImGui.text("Search:");
        ImGui.sameLine();

        ImGui.pushItemWidth(SEARCH_BAR_WIDTH);
        boolean textChanged = ImGui.inputTextWithHint("##search", "Search models, blocks, items...", state.getSearchText());
        ImGui.popItemWidth();

        // Clear button
        if (!state.getSearchTextValue().isEmpty()) {
            ImGui.sameLine();
            if (ImGui.smallButton("Clear")) {
                state.setSearchTextValue("");
            }
        }
    }

    /**
     * Renders the filter combo box with enhanced styling.
     */
    private void renderFilterCombo() {
        ModelBrowserState state = controller.getState();
        FilterType currentFilter = state.getCurrentFilter();

        ImGui.alignTextToFramePadding();
        ImGui.text("Filter:");
        ImGui.sameLine();

        // Highlight active filter
        if (currentFilter != FilterType.ALL_MODELS) {
            ImGui.pushStyleColor(ImGuiCol.FrameBg, 0.3f, 0.5f, 0.7f, 0.5f);
        }

        ImGui.pushItemWidth(FILTER_WIDTH);
        ImGui.combo("##filter", state.getCurrentFilterIndex(), state.getFilters());
        ImGui.popItemWidth();

        if (currentFilter != FilterType.ALL_MODELS) {
            ImGui.popStyleColor();
        }
    }

    /**
     * Renders the status footer with selection info, tips, and shortcuts.
     */
    private void renderStatusFooter() {
        ModelBrowserState state = controller.getState();

        // Selected model info
        ImGui.textWrapped(state.getSelectedModelInfo());

        // Search results count
        if (state.isSearchActive()) {
            ImGui.sameLine();
            ImGui.textDisabled(" | Searching: \"" + state.getSearchTextValue() + "\"");
        }

        // Current view mode indicator
        ImGui.sameLine();
        String viewModeText = switch (state.getViewMode()) {
            case GRID -> "Grid";
            case LIST -> "List";
            case COMPACT -> "Compact";
        };
        ImGui.textDisabled(" | View: " + viewModeText);

        // Helpful tip (far right)
        ImGui.sameLine();
        float availWidth = ImGui.getContentRegionAvailX();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + availWidth - 180);
        ImGui.textDisabled("Tip: Right-click items for options");
    }

    /**
     * Renders the main model tree view.
     */
    private void renderModelTree() {
        FilterType currentFilter = controller.getState().getCurrentFilter();

        // Entity Models section
        if (currentFilter.showEntityModels()) {
            if (ImGui.treeNodeEx("Entity Models", ImGuiTreeNodeFlags.DefaultOpen)) {
                renderEntityModels();
                ImGui.treePop();
            }
        }

        // Blocks section
        if (currentFilter.showBlocks()) {
            if (ImGui.treeNodeEx("Blocks", ImGuiTreeNodeFlags.DefaultOpen)) {
                renderBlocksTree();
                ImGui.treePop();
            }
        }

        // Items section
        if (currentFilter.showItems()) {
            if (ImGui.treeNode("Items")) {
                renderItemsTree();
                ImGui.treePop();
            }
        }

        // Recent files section
        if (currentFilter.showRecentFiles()) {
            if (ImGui.treeNode("Recent Files")) {
                renderRecentFiles();
                ImGui.treePop();
            }
        }
    }

    /**
     * Renders the entity models section.
     */
    private void renderEntityModels() {
        if (ImGui.treeNode("Cow Models")) {
            String[] recentFiles = controller.getState().getRecentFilesArray();
            for (String fileName : recentFiles) {
                if (fileName.toLowerCase().contains("cow")) {
                    if (ImGui.selectable(fileName, false)) {
                        controller.selectModel(fileName);
                    }
                }
            }
            ImGui.treePop();
        }
    }

    /**
     * Renders the blocks tree with categorization and enhanced styling.
     */
    private void renderBlocksTree() {
        Map<BlockCategorizer.Category, List<BlockType>> categorized = controller.getCategorizedBlocks();
        int totalVisibleBlocks = 0;

        for (BlockCategorizer.Category category : BlockCategorizer.Category.values()) {
            List<BlockType> blocks = categorized.get(category);
            if (blocks == null || blocks.isEmpty()) {
                continue;
            }

            // Apply search filter
            List<BlockType> filtered = controller.filterBlocks(blocks);
            if (filtered.isEmpty()) {
                continue;
            }

            totalVisibleBlocks += filtered.size();

            // Category header with count
            String categoryLabel = category.getDisplayName() + " (" + filtered.size() + ")";
            if (ImGui.treeNode(categoryLabel)) {
                renderCategoryItems(filtered, block -> {
                    String displayName = BlockManager.getDisplayName(block);
                    if (ImGui.selectable(displayName, false)) {
                        controller.selectBlock(block);
                    }
                });
                ImGui.treePop();
            }
        }

        // Show "no results" message if search is active and nothing found
        if (totalVisibleBlocks == 0 && controller.getState().isSearchActive()) {
            ImGui.indent(INDENT_SPACING);
            ImGui.textDisabled("No blocks match your search");
            ImGui.unindent(INDENT_SPACING);
        }
    }

    /**
     * Renders the items tree with categorization and enhanced styling.
     */
    private void renderItemsTree() {
        Map<ItemCategorizer.Category, List<ItemType>> categorized = controller.getCategorizedItems();
        int totalVisibleItems = 0;

        for (ItemCategorizer.Category category : ItemCategorizer.Category.values()) {
            List<ItemType> items = categorized.get(category);
            if (items == null || items.isEmpty()) {
                continue;
            }

            // Apply search filter
            List<ItemType> filtered = controller.filterItems(items);
            if (filtered.isEmpty()) {
                continue;
            }

            totalVisibleItems += filtered.size();

            // Category header with count
            String categoryLabel = category.getDisplayName() + " (" + filtered.size() + ")";
            if (ImGui.treeNode(categoryLabel)) {
                renderCategoryItems(filtered, item -> {
                    String displayName = ItemManager.getDisplayName(item);
                    if (ImGui.selectable(displayName, false)) {
                        controller.selectItem(item);
                    }
                });
                ImGui.treePop();
            }
        }

        // Show "no results" message if search is active and nothing found
        if (totalVisibleItems == 0 && controller.getState().isSearchActive()) {
            ImGui.indent(INDENT_SPACING);
            ImGui.textDisabled("No items match your search");
            ImGui.unindent(INDENT_SPACING);
        }
    }

    /**
     * Generic category items renderer following DRY principle.
     * Renders a list of items with consistent spacing and styling.
     *
     * @param items The list of items to render
     * @param itemRenderer Consumer that renders each individual item
     * @param <T> The type of items being rendered
     */
    private <T> void renderCategoryItems(List<T> items, java.util.function.Consumer<T> itemRenderer) {
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, CONTROL_SPACING);
        for (T item : items) {
            itemRenderer.accept(item);
        }
        ImGui.popStyleVar();
    }

    /**
     * Renders the recent files list with enhanced styling.
     */
    private void renderRecentFiles() {
        List<String> recentFiles = controller.getState().getRecentFiles();

        if (recentFiles.isEmpty()) {
            ImGui.indent(INDENT_SPACING);
            ImGui.textDisabled("No recent files");
            ImGui.unindent(INDENT_SPACING);
            return;
        }

        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, CONTROL_SPACING);
        for (String fileName : recentFiles) {
            if (ImGui.selectable(fileName, false)) {
                controller.loadRecentFile(fileName);
            }
        }
        ImGui.popStyleVar();
    }

    /**
     * Gets the controller for this component.
     *
     * @return The model browser controller
     */
    public ModelBrowserController getController() {
        return controller;
    }

    /**
     * Checks if the window is visible.
     *
     * @return true if visible
     */
    public boolean isVisible() {
        return visible.get();
    }

    /**
     * Sets the window visibility.
     *
     * @param visible true to show, false to hide
     */
    public void setVisible(boolean visible) {
        this.visible.set(visible);
    }
}
