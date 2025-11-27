package com.openmason.ui.viewport.views;

import com.openmason.ui.ViewportController;
import com.openmason.ui.preferences.PreferencesManager;
import com.openmason.ui.themes.application.DensityManager;
import com.openmason.ui.themes.core.ThemeManager;
import com.openmason.ui.viewport.ViewportActions;
import com.openmason.ui.viewport.ViewportUIState;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTabBarFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the main 3D viewport window.
 * Follows Single Responsibility Principle - only renders the main viewport UI.
 */
public class ViewportMainView {

    private static final Logger logger = LoggerFactory.getLogger(ViewportMainView.class);

    private final ViewportUIState state;
    private final ViewportActions actions;
    private final ViewportController viewport;
    private final ThemeManager themeManager;
    private final PreferencesManager preferencesManager;

    private final ImVec2 viewportSize = new ImVec2();
    private final ImVec2 viewportPos = new ImVec2();

    // Theme integration
    private int styleVarPushCount = 0;

    public ViewportMainView(ViewportUIState state, ViewportActions actions,
                           ViewportController viewport, ThemeManager themeManager,
                           PreferencesManager preferencesManager) {
        this.state = state;
        this.actions = actions;
        this.viewport = viewport;
        this.themeManager = themeManager;
        this.preferencesManager = preferencesManager;
    }

    /**
     * Render the main viewport window.
     */
    public void render() {
        if (ImGui.begin("3D Viewport")) {
            renderToolbar();
            ImGui.separator();
            renderViewport3D();
        }
        ImGui.end();
    }

    /**
     * Render viewport toolbar with tab-based organization.
     * Organizes controls into View, Display, and Tools tabs for professional appearance.
     */
    private void renderToolbar() {
        int tabBarFlags = ImGuiTabBarFlags.None;

        if (ImGui.beginTabBar("##ViewportToolbar", tabBarFlags)) {

            // View Tab (most frequently used controls)
            if (ImGui.beginTabItem("View")) {
                renderViewTab();
                ImGui.endTabItem();
            }

            // Display Tab (visual aids)
            if (ImGui.beginTabItem("Display")) {
                renderDisplayTab();
                ImGui.endTabItem();
            }

            // Tools Tab (advanced panels)
            if (ImGui.beginTabItem("Tools")) {
                renderToolsTab();
                ImGui.endTabItem();
            }

            ImGui.endTabBar();
        }
    }

    /**
     * Render actual 3D viewport content.
     */
    private void renderViewport3D() {
        // Get available content region
        ImGui.getContentRegionAvail(viewportSize);
        ImGui.getCursorScreenPos(viewportPos);

        // Ensure minimum size
        if (viewportSize.x < 400) viewportSize.x = 400;
        if (viewportSize.y < 300) viewportSize.y = 300;

        // Resize viewport if needed
        viewport.resize((int) viewportSize.x, (int) viewportSize.y);

        // Render 3D content
        viewport.render();

        // Display the rendered texture with mouse capture functionality
        int colorTexture = viewport.getColorTexture();
        if (colorTexture == -1) {
            ImGui.text("Viewport texture not available");
            return;
        }

        // Get image position before drawing for manual bounds checking
        ImVec2 imagePos = ImGui.getCursorScreenPos();

        // Display the rendered texture directly without any widgets
        ImGui.image(colorTexture, viewportSize.x, viewportSize.y, 0, 1, 1, 0);

        // Check if the viewport window itself is being hovered
        boolean viewportHovered = ImGui.isWindowHovered();

        // Handle input after image
        if (viewport.getInputHandler() != null) {
            viewport.getInputHandler().handleInput(imagePos, viewportSize.x, viewportSize.y, viewportHovered);
        }
    }

    /**
     * Render the View tab with view mode, render mode, reset, and fit controls.
     */
    private void renderViewTab() {
        applyDensityScaling();

        // View mode and Render mode (horizontal layout)
        ImGui.text("View:");
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        if (ImGui.combo("##viewmode", state.getCurrentViewModeIndex(), state.getViewModes())) {
            actions.updateViewMode();
        }

        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();

        ImGui.text("Render:");
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        if (ImGui.combo("##rendermode", state.getCurrentRenderModeIndex(), state.getRenderModes())) {
            actions.updateRenderMode();
        }

        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();

        ImGui.text("Camera:");
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        if (ImGui.combo("##cameramode", state.getCurrentCameraModeIndex(), state.getCameraModes())) {
            actions.updateCameraMode();
        }

        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();

        if (ImGui.button("Reset", 80, 0)) {
            actions.resetView();
        }
        ImGui.sameLine();
        if (ImGui.button("Fit", 80, 0)) {
            actions.fitToView();
        }

        popDensityScaling();
    }

    /**
     * Render the Display tab with visual aids checkboxes.
     */
    private void renderDisplayTab() {
        applyDensityScaling();

        // Checkboxes (horizontal layout for space efficiency)
        if (ImGui.checkbox("Grid", state.getGridVisible())) {
            actions.toggleGrid();
        }

        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();

        if (ImGui.checkbox("Axes", state.getAxesVisible())) {
            actions.toggleAxes();
        }

        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();

        if (ImGui.checkbox("Snapping", state.getGridSnappingEnabled())) {
            actions.toggleGridSnapping();
        }

        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();

        if (ImGui.checkbox("Mesh", state.getShowVertices())) {
            actions.toggleShowVertices();
        }

        popDensityScaling();
    }

    /**
     * Render the Tools tab with advanced panel toggles.
     */
    private void renderToolsTab() {
        applyDensityScaling();

        // Panel toggle buttons (horizontal layout for space efficiency)
        boolean cameraOpen = state.getShowCameraControls().get();
        if (ImGui.button("Camera", 120, 0)) {
            state.getShowCameraControls().set(!cameraOpen);
        }

        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();

        boolean renderingOpen = state.getShowRenderingOptions().get();
        if (ImGui.button("Rendering", 120, 0)) {
            state.getShowRenderingOptions().set(!renderingOpen);
        }

        ImGui.sameLine();
        ImGui.spacing();
        ImGui.sameLine();

        boolean transformOpen = state.getShowTransformationControls().get();
        if (ImGui.button("Transform", 120, 0)) {
            state.getShowTransformationControls().set(!transformOpen);
        }

        popDensityScaling();
    }

    /**
     * Apply density scaling to UI elements.
     */
    private void applyDensityScaling() {
        DensityManager.UIDensity density = getDensity();
        float scale = density.getScaleFactor();

        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8.0f * scale, 4.0f * scale);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 4.0f * scale, 4.0f * scale);
        styleVarPushCount = 2;
    }

    /**
     * Pop density scaling style variables.
     */
    private void popDensityScaling() {
        ImGui.popStyleVar(styleVarPushCount);
    }

    /**
     * Get section header color (light blue tint).
     */
    private ImVec4 getSectionHeaderColor() {
        return new ImVec4(0.8f, 0.8f, 1.0f, 1.0f);
    }

    /**
     * Get current UI density from preferences.
     */
    private DensityManager.UIDensity getDensity() {
        // Try to get from preferences manager
        if (preferencesManager != null) {
            try {
                // Get current density from preferences if available
                return DensityManager.UIDensity.NORMAL; // Default to NORMAL for now
            } catch (Exception e) {
                logger.warn("Failed to get density from preferences, using NORMAL", e);
            }
        }
        return DensityManager.UIDensity.NORMAL;
    }
}
