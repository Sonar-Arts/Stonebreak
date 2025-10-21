package com.openmason.ui.components.modelBrowser;

import com.openmason.block.BlockManager;
import com.openmason.item.ItemManager;
import com.openmason.ui.components.modelBrowser.categorizers.BlockCategorizer;
import com.openmason.ui.components.modelBrowser.categorizers.ItemCategorizer;
import com.openmason.ui.config.WindowConfig;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import imgui.ImGui;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImBoolean;
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

    private final ModelBrowserController controller;
    private final WindowConfig windowConfig;
    private final ImBoolean visible;

    /**
     * Creates a new Model Browser UI component.
     *
     * @param controller The controller managing business logic
     * @param visible The visibility state (shared with UIVisibilityState)
     */
    public ModelBrowserImGui(ModelBrowserController controller, ImBoolean visible) {
        this.controller = controller;
        this.windowConfig = WindowConfig.forModelBrowser();
        this.visible = visible;
        logger.debug("ModelBrowserImGui initialized");
    }

    /**
     * Renders the Model Browser window.
     * Should be called every frame when the window should be visible.
     */
    public void render() {
        if (!visible.get()) {
            return;
        }

        if (ImGui.begin("Model Browser", visible)) {
            renderModelBrowserControls();
            ImGui.separator();
            renderModelTree();
            ImGui.separator();
            renderModelInfo();
        }
        ImGui.end();
    }

    /**
     * Renders the search and filter controls.
     */
    private void renderModelBrowserControls() {
        ModelBrowserState state = controller.getState();

        // Search bar
        ImGui.text("Search:");
        ImGui.sameLine();
        ImGui.pushItemWidth(200);
        ImGui.inputText("##search", state.getSearchText());
        ImGui.popItemWidth();

        // Filter dropdown
        ImGui.sameLine();
        ImGui.text("Filter:");
        ImGui.sameLine();
        ImGui.pushItemWidth(150);
        ImGui.combo("##filter", state.getCurrentFilterIndex(), state.getFilters());
        ImGui.popItemWidth();
    }

    /**
     * Renders the main model tree view.
     */
    private void renderModelTree() {
        String currentFilter = controller.getState().getCurrentFilterName();

        // Entity Models section
        if (currentFilter.equals("All Models") || currentFilter.equals("Cow Models")) {
            if (ImGui.treeNodeEx("Entity Models", ImGuiTreeNodeFlags.DefaultOpen)) {
                renderEntityModels();
                ImGui.treePop();
            }
        }

        // Blocks section
        if (currentFilter.equals("All Models")) {
            if (ImGui.treeNodeEx("Blocks", ImGuiTreeNodeFlags.DefaultOpen)) {
                renderBlocksTree();
                ImGui.treePop();
            }
        }

        // Items section
        if (currentFilter.equals("All Models")) {
            if (ImGui.treeNode("Items")) {
                renderItemsTree();
                ImGui.treePop();
            }
        }

        // Recent files section
        if (currentFilter.equals("All Models") || currentFilter.equals("Recent Files")) {
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
     * Renders the blocks tree with categorization.
     */
    private void renderBlocksTree() {
        Map<BlockCategorizer.Category, List<BlockType>> categorized = controller.getCategorizedBlocks();

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

            if (ImGui.treeNode(category.getDisplayName())) {
                for (BlockType block : filtered) {
                    String displayName = BlockManager.getDisplayName(block);
                    if (ImGui.selectable(displayName, false)) {
                        controller.selectBlock(block);
                    }
                }
                ImGui.treePop();
            }
        }
    }

    /**
     * Renders the items tree with categorization.
     */
    private void renderItemsTree() {
        Map<ItemCategorizer.Category, List<ItemType>> categorized = controller.getCategorizedItems();

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

            if (ImGui.treeNode(category.getDisplayName())) {
                for (ItemType item : filtered) {
                    String displayName = ItemManager.getDisplayName(item);
                    if (ImGui.selectable(displayName, false)) {
                        controller.selectItem(item);
                    }
                }
                ImGui.treePop();
            }
        }
    }

    /**
     * Renders the recent files list.
     */
    private void renderRecentFiles() {
        List<String> recentFiles = controller.getState().getRecentFiles();

        if (recentFiles.isEmpty()) {
            ImGui.textDisabled("No recent files");
            return;
        }

        for (String fileName : recentFiles) {
            if (ImGui.selectable(fileName, false)) {
                controller.loadRecentFile(fileName);
            }
        }
    }

    /**
     * Renders the model information panel.
     */
    private void renderModelInfo() {
        String info = controller.getState().getSelectedModelInfo();
        ImGui.textWrapped(info);
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
     * Gets the window configuration.
     *
     * @return The window configuration
     */
    public WindowConfig getWindowConfig() {
        return windowConfig;
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
