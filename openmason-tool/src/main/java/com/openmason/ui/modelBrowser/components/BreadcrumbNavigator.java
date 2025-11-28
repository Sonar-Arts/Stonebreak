package com.openmason.ui.modelBrowser.components;

import com.openmason.ui.modelBrowser.ModelBrowserController;
import com.openmason.ui.modelBrowser.ModelBrowserState;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Renders breadcrumb navigation for the Model Browser.
 *
 * <p>Displays the current navigation path as clickable segments:</p>
 * <pre>Home > Blocks > Terrain</pre>
 *
 * <p>Users can click on any segment to navigate back to that level.
 * Similar to Windows Explorer's address bar breadcrumb mode.</p>
 *
 * <p>Following SOLID principles:</p>
 * <ul>
 *   <li><strong>Single Responsibility</strong>: Only renders breadcrumb navigation</li>
 *   <li><strong>KISS</strong>: Simple, focused implementation</li>
 * </ul>
 */
public class BreadcrumbNavigator {

    private static final Logger logger = LoggerFactory.getLogger(BreadcrumbNavigator.class);

    // UI constants
    private static final String SEPARATOR = " > ";
    private static final float SEPARATOR_SPACING = 4.0f;

    private final ModelBrowserController controller;

    /**
     * Creates a new breadcrumb navigator.
     *
     * @param controller The controller managing business logic
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
        ImGui.pushStyleColor(ImGuiCol.Button, 0, 0, 0, 0); // Transparent background
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.26f, 0.59f, 0.98f, 0.2f); // Subtle hover
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.26f, 0.59f, 0.98f, 0.4f); // Subtle active

        if (ImGui.smallButton(segment)) {
            // Navigate to this segment
            controller.getState().navigateTo(segment);

            // Update selected category based on path
            updateCategoryFromPath(segment);
        }

        ImGui.popStyleColor(3);
    }

    /**
     * Updates the selected category when navigating via breadcrumb.
     *
     * @param segment The breadcrumb segment that was clicked
     */
    private void updateCategoryFromPath(String segment) {
        ModelBrowserState state = controller.getState();
        List<String> currentPath = state.getNavigationPath();

        // Reconstruct category from path
        if (currentPath.size() == 1) {
            // Just "Home"
            state.setSelectedCategory("All Models");
        } else if (currentPath.size() == 2) {
            // "Home" > "Blocks" or "Home" > "Items" etc.
            state.setSelectedCategory(currentPath.get(1));
        } else if (currentPath.size() >= 3) {
            // "Home" > "Blocks" > "Terrain"
            String parent = currentPath.get(1);
            String category = currentPath.get(2);
            state.setSelectedCategory(parent + " > " + category);
        }
    }

    /**
     * Renders a compact version with ellipsis for long paths.
     * Useful when space is limited.
     */
    public void renderCompact() {
        ModelBrowserState state = controller.getState();
        List<String> path = state.getNavigationPath();

        if (path.isEmpty()) {
            ImGui.textDisabled("...");
            return;
        }

        // Show first and last segments with ellipsis in between if path is long
        if (path.size() <= 3) {
            render(); // Use full rendering for short paths
        } else {
            // First segment
            renderClickableSegment(path.get(0));
            ImGui.sameLine();
            ImGui.textDisabled(" > ... > ");
            ImGui.sameLine();

            // Last segment
            ImGui.text(path.get(path.size() - 1));
        }
    }

    /**
     * Gets the current path as a string for display.
     *
     * @return Path string like "Home > Blocks > Terrain"
     */
    public String getPathString() {
        return controller.getState().getNavigationPathString();
    }
}
