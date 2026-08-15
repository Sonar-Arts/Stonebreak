package com.openmason.main.systems.services;

import com.openmason.main.systems.stateHandling.UIVisibilityState;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.ViewportController;
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

    private com.openmason.main.systems.layout.MainLayoutBuilder layoutBuilder;

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

        // Rebuild the dock layout on the next frame rather than deleting imgui.ini.
        // Deleting it did not actually work: ImGui rewrites the file from its in-memory
        // state on exit, so the layout came back — and it forced a restart for no reason.
        if (layoutBuilder != null) {
            layoutBuilder.requestReset();
            statusService.updateStatus("Layout reset to default");
        } else {
            logger.warn("No layout builder wired; cannot reset the dock layout");
            statusService.updateStatus("Layout reset unavailable");
        }
    }

    /** Supplied by the shell so a reset can rebuild the dockspace in place. */
    public void setLayoutBuilder(com.openmason.main.systems.layout.MainLayoutBuilder builder) {
        this.layoutBuilder = builder;
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
            viewport.setUnrenderedMode(false);
        }

        viewportState.getGridVisible().set(true);
        viewportState.getAxesVisible().set(true);
        viewportState.getUnrenderedMode().set(false);

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
            viewport.setUnrenderedMode(false);
        }

        viewportState.getGridVisible().set(false);
        viewportState.getAxesVisible().set(false);
        viewportState.getUnrenderedMode().set(false);

        statusService.updateStatus("Texturing layout applied");
    }
}
