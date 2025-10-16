package com.openmason.ui.menus;

import com.openmason.ui.services.LayoutService;
import com.openmason.ui.services.ViewportOperationService;
import com.openmason.ui.state.UIVisibilityState;
import com.openmason.ui.state.ViewportState;
import com.openmason.ui.viewport.OpenMason3DViewport;
import imgui.ImGui;

/**
 * View menu handler.
 * Follows Single Responsibility Principle - only handles view menu operations.
 */
public class ViewMenuHandler {

    private final UIVisibilityState uiState;
    private final ViewportState viewportState;
    private final ViewportOperationService viewportOperations;
    private final LayoutService layoutService;

    private OpenMason3DViewport viewport;

    public ViewMenuHandler(UIVisibilityState uiState, ViewportState viewportState,
                           ViewportOperationService viewportOperations, LayoutService layoutService) {
        this.uiState = uiState;
        this.viewportState = viewportState;
        this.viewportOperations = viewportOperations;
        this.layoutService = layoutService;
    }

    /**
     * Set viewport reference.
     */
    public void setViewport(OpenMason3DViewport viewport) {
        this.viewport = viewport;
    }

    /**
     * Render the view menu.
     */
    public void render() {
        if (!ImGui.beginMenu("View")) {
            return;
        }

        if (ImGui.menuItem("Reset View", "Ctrl+R")) {
            viewportOperations.resetView(viewport);
        }

        if (ImGui.menuItem("Fit to View", "Ctrl+F")) {
            viewportOperations.fitToView();
        }

        ImGui.separator();

        if (ImGui.menuItem("Show Grid", "Ctrl+G", viewportState.isShowGrid())) {
            viewportOperations.toggleGrid(viewport);
        }

        if (ImGui.menuItem("Show Axes", "Ctrl+X", viewportState.isShowAxes())) {
            viewportOperations.toggleAxes(viewport);
        }

        if (ImGui.menuItem("Wireframe Mode", "Ctrl+W", viewportState.isWireframeMode())) {
            viewportOperations.toggleWireframe(viewport);
        }

        boolean gizmoEnabled = (viewport != null) ? viewport.isGizmoEnabled() : false;
        if (ImGui.menuItem("Transform Gizmo", "Ctrl+T", gizmoEnabled)) {
            viewportOperations.toggleTransformGizmo(viewport);
        }

        ImGui.separator();

        if (ImGui.menuItem("Show 3D Viewport", "Ctrl+1", true)) {
            // Viewport always visible in current implementation
        }

        if (ImGui.menuItem("Show Model Browser", "Ctrl+2", uiState.getShowModelBrowser().get())) {
            uiState.toggleModelBrowser();
        }

        if (ImGui.menuItem("Show Property Panel", "Ctrl+3", uiState.getShowPropertyPanel().get())) {
            uiState.togglePropertyPanel();
        }

        if (ImGui.menuItem("Show Toolbar", "Ctrl+5", uiState.getShowToolbar().get())) {
            uiState.toggleToolbar();
        }

        ImGui.separator();

        if (ImGui.beginMenu("Layout")) {
            if (ImGui.menuItem("Reset to Default")) {
                layoutService.resetToDefault();
            }

            if (ImGui.menuItem("Full Screen Viewport", "F11")) {
                layoutService.toggleFullscreenViewport();
            }

            if (ImGui.menuItem("Modeling Layout")) {
                layoutService.applyModelingLayout(viewport);
            }

            if (ImGui.menuItem("Texturing Layout")) {
                layoutService.applyTexturingLayout(viewport);
            }

            ImGui.endMenu();
        }

        ImGui.endMenu();
    }
}
