package com.openmason.ui.viewport.views;

import com.openmason.ui.ViewportController;
import com.openmason.ui.viewport.ViewportActions;
import com.openmason.ui.viewport.ViewportUIState;
import imgui.ImGui;
import imgui.ImVec2;
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

    private final ImVec2 viewportSize = new ImVec2();
    private final ImVec2 viewportPos = new ImVec2();

    public ViewportMainView(ViewportUIState state, ViewportActions actions, ViewportController viewport) {
        this.state = state;
        this.actions = actions;
        this.viewport = viewport;
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
     * Render viewport toolbar with view and render mode controls.
     */
    private void renderToolbar() {
        // View mode selection
        ImGui.text("View:");
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        if (ImGui.combo("##viewmode", state.getCurrentViewModeIndex(), state.getViewModes())) {
            actions.updateViewMode();
        }

        ImGui.sameLine();

        // Render mode selection
        ImGui.text("Render:");
        ImGui.sameLine();
        ImGui.setNextItemWidth(120);
        if (ImGui.combo("##rendermode", state.getCurrentRenderModeIndex(), state.getRenderModes())) {
            actions.updateRenderMode();
        }

        ImGui.sameLine();

        // View control buttons
        if (ImGui.button("Reset##viewport")) {
            actions.resetView();
        }

        ImGui.sameLine();

        if (ImGui.button("Fit##viewport")) {
            actions.fitToView();
        }

        ImGui.sameLine();

        // Toggle buttons
        if (ImGui.checkbox("Grid##viewport", state.getGridVisible())) {
            actions.toggleGrid();
        }

        ImGui.sameLine();

        if (ImGui.checkbox("Axes##viewport", state.getAxesVisible())) {
            actions.toggleAxes();
        }

        ImGui.sameLine();

        if (ImGui.checkbox("Grid Snapping##viewport", state.getGridSnappingEnabled())) {
            actions.toggleGridSnapping();
        }

        ImGui.sameLine();

        if (ImGui.checkbox("Mesh##viewport", state.getShowVertices())) {
            actions.toggleShowVertices();
        }

        // Additional controls toggle
        ImGui.sameLine();
        ImGui.separator();
        ImGui.sameLine();

        if (ImGui.button("Camera##viewport")) {
            state.getShowCameraControls().set(!state.getShowCameraControls().get());
        }

        ImGui.sameLine();

        if (ImGui.button("Rendering##viewport")) {
            state.getShowRenderingOptions().set(!state.getShowRenderingOptions().get());
        }

        ImGui.sameLine();

        if (ImGui.button("Transform##viewport")) {
            state.getShowTransformationControls().set(!state.getShowTransformationControls().get());
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
}
