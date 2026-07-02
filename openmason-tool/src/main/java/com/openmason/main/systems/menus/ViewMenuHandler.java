package com.openmason.main.systems.menus;

import com.openmason.main.systems.services.LayoutService;
import com.openmason.main.systems.services.ViewportOperationService;
import com.openmason.main.systems.stateHandling.UIVisibilityState;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.ViewportController;
import imgui.ImGui;

/**
 * View menu handler.
 */
public class ViewMenuHandler {

    private final UIVisibilityState uiState;
    private final ViewportUIState viewportState;
    private final ViewportOperationService viewportOperations;
    private final LayoutService layoutService;

    private ViewportController viewport;

    public ViewMenuHandler(UIVisibilityState uiState, ViewportUIState viewportState,
                           ViewportOperationService viewportOperations, LayoutService layoutService) {
        this.uiState = uiState;
        this.viewportState = viewportState;
        this.viewportOperations = viewportOperations;
        this.layoutService = layoutService;
    }

    /**
     * Set viewport reference.
     */
    public void setViewport(ViewportController viewport) {
        this.viewport = viewport;
    }

    /**
     * Render the view menu.
     */
    public void render() {
        if (!ImGui.beginMenu("View")) {
            return;
        }

        // --- Camera ---
        if (ImGui.menuItem("Reset View", "Ctrl+R")) {
            viewportOperations.resetView(viewport);
        }

        if (ImGui.menuItem("Fit to View", "Ctrl+F")) {
            viewportOperations.fitToView();
        }

        ImGui.separator();

        // --- Viewport overlays ---
        if (ImGui.menuItem("Show Grid", "Ctrl+G", viewportState.getGridVisible().get())) {
            viewportOperations.toggleGrid(viewport);
        }

        if (ImGui.menuItem("Show Axes", "Ctrl+Shift+A", viewportState.getAxesVisible().get())) {
            viewportOperations.toggleAxes(viewport);
        }

        if (ImGui.menuItem("Show Bones", "", viewportState.getShowBones().get())) {
            viewportState.toggleBones();
        }

        if (ImGui.menuItem("Unrendered Mode", "Ctrl+W", viewportState.getUnrenderedMode().get())) {
            viewportOperations.toggleUnrendered(viewport);
        }

        ImGui.separator();

        // --- Panels ---
        if (ImGui.menuItem("Project Browser", "", uiState.getShowModelBrowser().get())) {
            uiState.toggleModelBrowser();
        }

        if (ImGui.menuItem("Model Properties", "", uiState.getShowPropertyPanel().get())) {
            uiState.togglePropertyPanel();
        }

        if (ImGui.menuItem("Rigging", "", uiState.getShowRiggingPane().get())) {
            uiState.toggleRiggingPane();
        }

        if (ImGui.menuItem("Toolbar", "", uiState.getShowToolbar().get())) {
            uiState.toggleToolbar();
        }

        ImGui.separator();

        // --- Layout presets ---
        if (ImGui.beginMenu("Layout")) {
            if (ImGui.menuItem("Modeling Layout")) {
                layoutService.applyModelingLayout(viewport);
            }

            if (ImGui.menuItem("Texturing Layout")) {
                layoutService.applyTexturingLayout(viewport);
            }

            if (ImGui.menuItem("Full Screen Viewport")) {
                layoutService.toggleFullscreenViewport();
            }

            ImGui.separator();

            if (ImGui.menuItem("Reset to Default")) {
                layoutService.resetToDefault();
            }

            ImGui.endMenu();
        }

        ImGui.endMenu();
    }
}
