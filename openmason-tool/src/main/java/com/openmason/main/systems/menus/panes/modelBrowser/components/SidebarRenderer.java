package com.openmason.main.systems.menus.panes.modelBrowser.components;

import com.openmason.main.systems.menus.panes.modelBrowser.ModelBrowserController;
import com.openmason.main.systems.menus.panes.modelBrowser.ModelBrowserState;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;

import java.util.List;

/**
 * Sidebar navigation panel for the Model Browser.
 *
 * <p>The browser only ships file-backed asset categories now: the configured
 * .OMO library, the .SBT library, the union of both, and recent files.
 */
public record SidebarRenderer(ModelBrowserController controller) {

    public void render() {
        ModelBrowserState state = controller.getState();
        String selectedCategory = state.getSelectedCategory();

        if (renderCategoryItem("All Assets", selectedCategory)) {
            state.setSelectedCategory("All Assets");
            state.setNavigationPath(List.of("Home"));
        }

        ImGui.spacing();

        if (renderCategoryItem(".OMO Models", selectedCategory)) {
            state.setSelectedCategory(".OMO Models");
            state.setNavigationPath(List.of("Home", ".OMO Models"));
        }

        if (renderCategoryItem(".SBT Textures", selectedCategory)) {
            state.setSelectedCategory(".SBT Textures");
            state.setNavigationPath(List.of("Home", ".SBT Textures"));
        }

        ImGui.spacing();

        if (renderCategoryItem("Recent Files", selectedCategory)) {
            state.setSelectedCategory("Recent Files");
            state.setNavigationPath(List.of("Home", "Recent Files"));
        }

        ImGui.spacing();
        ImGui.separator();
        ImGui.spacing();

        if (ImGui.smallButton("Refresh")) {
            controller.refreshAssetFolders();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Re-read folder paths from preferences and rescan");
        }
    }

    private boolean renderCategoryItem(String displayName, String selectedCategory) {
        boolean isSelected = displayName.equals(selectedCategory);

        if (isSelected) {
            ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
            ImGui.pushStyleColor(ImGuiCol.Header, accent.x, accent.y, accent.z, 0.4f);
            ImGui.pushStyleColor(ImGuiCol.HeaderHovered, accent.x, accent.y, accent.z, 0.6f);
            ImGui.pushStyleColor(ImGuiCol.HeaderActive, accent.x, accent.y, accent.z, 0.8f);
        }

        boolean clicked = ImGui.selectable(displayName, isSelected);

        if (isSelected) {
            ImGui.popStyleColor(3);
        }
        return clicked;
    }
}
