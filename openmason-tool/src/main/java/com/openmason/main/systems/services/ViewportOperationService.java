package com.openmason.main.systems.services;

import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.viewport.ViewportUIState;

/**
 * Viewport operation service.
 * Follows Single Responsibility Principle - only handles viewport operations.
 * Follows DRY - eliminates duplicated viewport operation code.
 */
public class ViewportOperationService {

    private final ViewportUIState viewportState;
    private final StatusService statusService;

    public ViewportOperationService(ViewportUIState viewportState, StatusService statusService) {
        this.viewportState = viewportState;
        this.statusService = statusService;
    }

    /**
     * Reset viewport camera to default position.
     */
    public void resetView(ViewportController viewport) {
        if (viewport != null) {
            viewport.resetCamera();
        }
        statusService.updateStatus("View reset");
    }

    /**
     * Fit model to viewport.
     */
    public void fitToView() {
        statusService.updateStatus("Fitted to view");
    }

    /**
     * Toggle grid visibility.
     */
    public void toggleGrid(ViewportController viewport) {
        viewportState.toggleGrid();
        if (viewport != null) {
            viewport.setShowGrid(viewportState.getGridVisible().get());
        }
    }

    /**
     * Toggle axes visibility.
     */
    public void toggleAxes(ViewportController viewport) {
        viewportState.toggleAxes();
        if (viewport != null) {
            viewport.setAxesVisible(viewportState.getAxesVisible().get());
        }
    }

    /**
     * Toggle wireframe mode.
     */
    public void toggleWireframe(ViewportController viewport) {
        viewportState.toggleWireframe();
        if (viewport != null) {
            viewport.setWireframeMode(viewportState.getWireframeMode().get());
        }
        statusService.updateStatus("Wireframe " + (viewportState.getWireframeMode().get() ? "enabled" : "disabled"));
    }

}
