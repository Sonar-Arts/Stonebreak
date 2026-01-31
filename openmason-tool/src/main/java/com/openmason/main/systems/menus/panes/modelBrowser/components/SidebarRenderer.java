package com.openmason.main.systems.menus.panes.modelBrowser.components;

import com.openmason.main.systems.menus.panes.modelBrowser.ModelBrowserController;
import com.openmason.main.systems.menus.panes.modelBrowser.ModelBrowserState;
import com.openmason.main.systems.menus.panes.modelBrowser.categorizers.BlockCategorizer;
import com.openmason.main.systems.menus.panes.modelBrowser.categorizers.ItemCategorizer;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTreeNodeFlags;

import java.util.List;

/**
 * Renders the sidebar navigation panel for the Model Browser.
 */
public record SidebarRenderer(ModelBrowserController controller) {


    // UI constants
    private static final int TREE_NODE_FLAGS_DEFAULT = ImGuiTreeNodeFlags.OpenOnArrow | ImGuiTreeNodeFlags.OpenOnDoubleClick;

    /**
     * Creates a new sidebar renderer.
     */
    public SidebarRenderer {
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
            state.setNavigationPath(List.of("Home"));
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

        // .OMO Models section (custom models)
        if (renderCategoryItem(".OMO Models", selectedCategory)) {
            state.setSelectedCategory(".OMO Models");
            state.setNavigationPath(java.util.Arrays.asList("Home", ".OMO Models"));
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
     * @param displayName      The name to display
     * @param selectedCategory The currently selected category
     * @return true if the item was clicked
     */
    private boolean renderCategoryItem(String displayName, String selectedCategory) {
        return renderCategoryItem(displayName, selectedCategory, displayName);
    }

    /**
     * Renders a single category item with selection highlighting.
     *
     * @param displayName      The name to display
     * @param selectedCategory The currently selected category
     * @param categoryId       The category identifier for selection comparison
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
    @Override
    public ModelBrowserController controller() {
        return controller;
    }
}
