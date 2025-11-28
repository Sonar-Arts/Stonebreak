package com.openmason.ui.services;

import com.openmason.ui.state.UIVisibilityState;
import com.openmason.ui.viewport.ViewportUIState;
import com.openmason.ui.ViewportController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Layout management service.
 * Follows Single Responsibility Principle - only handles UI layout operations.
 */
public class LayoutService {

    private static final Logger logger = LoggerFactory.getLogger(LayoutService.class);

    private final UIVisibilityState uiState;
    private final ViewportUIState viewportState;
    private final StatusService statusService;

    public LayoutService(UIVisibilityState uiState, ViewportUIState viewportState, StatusService statusService) {
        this.uiState = uiState;
        this.viewportState = viewportState;
        this.statusService = statusService;
    }

    /**
     * Reset to default layout.
     */
    public void resetToDefault() {
        statusService.updateStatus("Resetting to default layout...");

        uiState.resetToDefault();

        // Reset ImGui docking layout by deleting imgui.ini
        try {
            Path iniPath = Paths.get("openmason-tool/imgui.ini");
            if (Files.exists(iniPath)) {
                Files.delete(iniPath);
                logger.info("Deleted ImGui layout configuration for reset");
            }
            statusService.updateStatus("Layout reset - restart application to see changes");
        } catch (Exception e) {
            logger.error("Failed to reset layout", e);
            statusService.updateStatus("Failed to reset layout: " + e.getMessage());
        }
    }

    /**
     * Toggle fullscreen viewport mode.
     */
    public void toggleFullscreenViewport() {
        uiState.toggleFullscreenViewport();
        statusService.updateStatus("Full screen viewport mode toggled");
    }

    /**
     * Apply modeling layout preset.
     */
    public void applyModelingLayout(ViewportController viewport) {
        statusService.updateStatus("Applying modeling layout...");

        uiState.getShowModelBrowser().set(true);
        uiState.getShowPropertyPanel().set(true);
        uiState.getShowToolbar().set(true);

        if (viewport != null) {
            viewport.setShowGrid(true);
            viewport.setAxesVisible(true);
            viewport.setWireframeMode(false);
        }

        viewportState.getGridVisible().set(true);
        viewportState.getAxesVisible().set(true);
        viewportState.getWireframeMode().set(false);

        statusService.updateStatus("Modeling layout applied");
    }

    /**
     * Apply texturing layout preset.
     */
    public void applyTexturingLayout(ViewportController viewport) {
        statusService.updateStatus("Applying texturing layout...");

        uiState.getShowModelBrowser().set(true);
        uiState.getShowPropertyPanel().set(true);
        uiState.getShowToolbar().set(true);

        if (viewport != null) {
            viewport.setShowGrid(false);
            viewport.setAxesVisible(false);
            viewport.setWireframeMode(false);
        }

        viewportState.getGridVisible().set(false);
        viewportState.getAxesVisible().set(false);
        viewportState.getWireframeMode().set(false);

        statusService.updateStatus("Texturing layout applied");
    }
}
