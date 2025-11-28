package com.openmason.ui.services;

import com.openmason.ui.viewport.ViewportUIState;
import com.openmason.ui.state.TransformState;
import com.openmason.ui.ViewportController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Viewport operation service.
 * Follows Single Responsibility Principle - only handles viewport operations.
 * Follows DRY - eliminates duplicated viewport operation code.
 */
public class ViewportOperationService {

    private static final Logger logger = LoggerFactory.getLogger(ViewportOperationService.class);

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
            viewport.setGridVisible(viewportState.getGridVisible().get());
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

    /**
     * Toggle transform gizmo visibility.
     */
    public void toggleGizmo(ViewportController viewport) {
        viewportState.toggleGizmo();
        if (viewport != null && viewport.getGizmoRenderer() != null) {
            viewport.getGizmoRenderer().getGizmoState().setEnabled(viewportState.getShowGizmo().get());
        }
        statusService.updateStatus("Transform gizmo " + (viewportState.getShowGizmo().get() ? "enabled" : "disabled"));
    }

    /**
     * Switch texture variant.
     */
    public void switchTextureVariant(ViewportController viewport, TransformState transformState, String variantName) {
        int index = transformState.getVariantIndexByName(variantName);
        transformState.setCurrentTextureVariantIndex(index);

        if (viewport != null) {
            viewport.setCurrentTextureVariant(variantName.toLowerCase());
        }

        statusService.updateStatus("Switched to " + variantName + " variant");
    }

    /**
     * Change render mode (wireframe/solid/textured).
     */
    public void changeRenderMode(ViewportController viewport, ViewportUIState state) {
        String renderMode = state.getCurrentRenderMode();

        if (viewport != null) {
            switch (renderMode.toLowerCase()) {
                case "wireframe":
                    state.getWireframeMode().set(true);
                    viewport.setWireframeMode(true);
                    break;
                default:
                    state.getWireframeMode().set(false);
                    viewport.setWireframeMode(false);
                    break;
            }
            statusService.updateStatus("Render mode: " + renderMode);
        }
    }
}
