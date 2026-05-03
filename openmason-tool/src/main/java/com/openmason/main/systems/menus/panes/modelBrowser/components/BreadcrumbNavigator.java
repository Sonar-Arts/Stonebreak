package com.openmason.main.systems.menus.panes.modelBrowser.components;

import com.openmason.main.systems.menus.panes.modelBrowser.ModelBrowserController;
import com.openmason.main.systems.menus.panes.modelBrowser.ModelBrowserState;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;

import java.util.List;

/**
 * Renders breadcrumb navigation for the Model Browser.
 */
public class BreadcrumbNavigator {

    // UI constants
    private static final String SEPARATOR = " > ";
    private final ModelBrowserController controller;

    /**
     * Creates a new breadcrumb navigator.
     */
    public BreadcrumbNavigator(ModelBrowserController controller) {
        this.controller = controller;
    }

    /**
     * Renders the breadcrumb navigation bar.
     * Should be called every frame in the toolbar area.
     */
    public void render() {
        ModelBrowserState state = controller.getState();
        List<String> path = state.getNavigationPath();

        if (path.isEmpty()) {
            ImGui.textDisabled("No path");
            return;
        }

        // Render each segment
        for (int i = 0; i < path.size(); i++) {
            String segment = path.get(i);
            boolean isLast = (i == path.size() - 1);

            // Render clickable segment
            if (isLast) {
                // Current location - not clickable, highlighted
                ImGui.text(segment);
            } else {
                // Previous location - clickable as button
                renderClickableSegment(segment);
            }

            // Render separator (except after last segment)
            if (!isLast) {
                ImGui.sameLine();
                ImGui.spacing();
                ImGui.sameLine();
                ImGui.textDisabled(SEPARATOR);
                ImGui.sameLine();
                ImGui.spacing();
                ImGui.sameLine();
            }
        }
    }

    /**
     * Renders a clickable breadcrumb segment.
     *
     * @param segment The segment text to render
     */
    private void renderClickableSegment(String segment) {
        // Style as a subtle button
        ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        ImGui.pushStyleColor(ImGuiCol.Button, 0, 0, 0, 0); // Transparent background
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent.x, accent.y, accent.z, 0.2f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x, accent.y, accent.z, 0.4f);

        if (ImGui.smallButton(segment)) {
            // Navigate to this segment
            controller.getState().navigateTo(segment);

            // Update selected category based on path
            updateCategoryFromPath();
        }

        ImGui.popStyleColor(3);
    }

    /**
     * Updates the selected category when navigating via breadcrumb.
     *
     */
    private void updateCategoryFromPath() {
        ModelBrowserState state = controller.getState();
        List<String> currentPath = state.getNavigationPath();

        if (currentPath.size() == 1) {
            state.setSelectedCategory("All Assets");
        } else {
            state.setSelectedCategory(currentPath.get(currentPath.size() - 1));
        }
    }
}
