package com.openmason.ui.components.modelBrowser.components;

import com.openmason.ui.components.modelBrowser.ModelBrowserController;
import com.openmason.ui.components.modelBrowser.ModelBrowserState;
import com.openmason.ui.components.modelBrowser.categorizers.BlockCategorizer;
import com.openmason.ui.components.modelBrowser.categorizers.ItemCategorizer;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTreeNodeFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the sidebar navigation panel for the Model Browser.
 *
 * <p>Provides a Windows Explorer-style folder tree showing:</p>
 * <ul>
 *   <li>All Models - Show everything</li>
 *   <li>Blocks - Categorized by terrain, ore, wood, plants, other</li>
 *   <li>Items - Categorized by tools, materials, other</li>
 *   <li>Entity Models - 3D models like cows</li>
 *   <li>Recent Files - Recently opened models</li>
 * </ul>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li><strong>Single Responsibility</strong>: Only renders the sidebar navigation</li>
 *   <li><strong>Dependency Inversion</strong>: Depends on controller abstraction</li>
 * </ul>
 */
public class SidebarRenderer {

    private static final Logger logger = LoggerFactory.getLogger(SidebarRenderer.class);

    // UI constants
    private static final float INDENT_SPACING = 16.0f;
    private static final int TREE_NODE_FLAGS_LEAF = ImGuiTreeNodeFlags.Leaf | ImGuiTreeNodeFlags.NoTreePushOnOpen;
    private static final int TREE_NODE_FLAGS_DEFAULT = ImGuiTreeNodeFlags.OpenOnArrow | ImGuiTreeNodeFlags.OpenOnDoubleClick;

    private final ModelBrowserController controller;

    /**
     * Creates a new sidebar renderer.
     *
     * @param controller The controller managing business logic
     */
    public SidebarRenderer(ModelBrowserController controller) {
        this.controller = controller;
    }

    /**
     * Renders the sidebar navigation panel.
     * Should be called every frame within a child window.
     */
    public void render() {
        ModelBrowserState state = controller.getState();
        String selectedCategory = state.getSelectedCategory();

        // All Models - top-level category
        if (renderCategoryItem("All Models", selectedCategory)) {
            state.setSelectedCategory("All Models");
            state.setNavigationPath(java.util.Arrays.asList("Home"));
        }

        ImGui.spacing();

        // Blocks section with subcategories
        if (ImGui.treeNodeEx("Blocks", TREE_NODE_FLAGS_DEFAULT)) {
            renderBlockCategories(selectedCategory, state);
            ImGui.treePop();
        }

        // Items section with subcategories
        if (ImGui.treeNodeEx("Items", TREE_NODE_FLAGS_DEFAULT)) {
            renderItemCategories(selectedCategory, state);
            ImGui.treePop();
        }

        // Entity Models section
        if (renderCategoryItem("Entity Models", selectedCategory)) {
            state.setSelectedCategory("Entity Models");
            state.setNavigationPath(java.util.Arrays.asList("Home", "Entity Models"));
        }

        ImGui.spacing();

        // Recent Files section
        if (renderCategoryItem("Recent Files", selectedCategory)) {
            state.setSelectedCategory("Recent Files");
            state.setNavigationPath(java.util.Arrays.asList("Home", "Recent Files"));
        }
    }

    /**
     * Renders block subcategories.
     */
    private void renderBlockCategories(String selectedCategory, ModelBrowserState state) {
        for (BlockCategorizer.Category category : BlockCategorizer.Category.values()) {
            String categoryName = "Blocks > " + category.getDisplayName();

            if (renderCategoryItem(category.getDisplayName(), selectedCategory, categoryName)) {
                state.setSelectedCategory(categoryName);
                state.setNavigationPath(java.util.Arrays.asList("Home", "Blocks", category.getDisplayName()));
            }
        }
    }

    /**
     * Renders item subcategories.
     */
    private void renderItemCategories(String selectedCategory, ModelBrowserState state) {
        for (ItemCategorizer.Category category : ItemCategorizer.Category.values()) {
            String categoryName = "Items > " + category.getDisplayName();

            if (renderCategoryItem(category.getDisplayName(), selectedCategory, categoryName)) {
                state.setSelectedCategory(categoryName);
                state.setNavigationPath(java.util.Arrays.asList("Home", "Items", category.getDisplayName()));
            }
        }
    }

    /**
     * Renders a single category item with selection highlighting.
     *
     * @param displayName The name to display
     * @param selectedCategory The currently selected category
     * @return true if the item was clicked
     */
    private boolean renderCategoryItem(String displayName, String selectedCategory) {
        return renderCategoryItem(displayName, selectedCategory, displayName);
    }

    /**
     * Renders a single category item with selection highlighting.
     *
     * @param displayName The name to display
     * @param selectedCategory The currently selected category
     * @param categoryId The category identifier for selection comparison
     * @return true if the item was clicked
     */
    private boolean renderCategoryItem(String displayName, String selectedCategory, String categoryId) {
        boolean isSelected = categoryId.equals(selectedCategory);

        // Highlight selected item
        if (isSelected) {
            ImGui.pushStyleColor(ImGuiCol.Header, 0.26f, 0.59f, 0.98f, 0.4f);
            ImGui.pushStyleColor(ImGuiCol.HeaderHovered, 0.26f, 0.59f, 0.98f, 0.6f);
            ImGui.pushStyleColor(ImGuiCol.HeaderActive, 0.26f, 0.59f, 0.98f, 0.8f);
        }

        // Use selectable for clickable items
        boolean clicked = ImGui.selectable(displayName, isSelected);

        if (isSelected) {
            ImGui.popStyleColor(3);
        }

        return clicked;
    }

    /**
     * Gets the controller for this renderer.
     *
     * @return The model browser controller
     */
    public ModelBrowserController getController() {
        return controller;
    }
}
