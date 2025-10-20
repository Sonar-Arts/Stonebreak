package com.openmason.ui.services;

import com.openmason.ui.state.ViewportState;
import com.openmason.ui.state.TransformState;
import com.openmason.ui.viewport.OpenMason3DViewport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Viewport operation service.
 * Follows Single Responsibility Principle - only handles viewport operations.
 * Follows DRY - eliminates duplicated viewport operation code.
 */
public class ViewportOperationService {

    private static final Logger logger = LoggerFactory.getLogger(ViewportOperationService.class);

    private final ViewportState viewportState;
    private final StatusService statusService;

    public ViewportOperationService(ViewportState viewportState, StatusService statusService) {
        this.viewportState = viewportState;
        this.statusService = statusService;
    }

    /**
     * Reset viewport camera to default position.
     */
    public void resetView(OpenMason3DViewport viewport) {
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
    public void toggleGrid(OpenMason3DViewport viewport) {
        viewportState.toggleGrid();
        if (viewport != null) {
            viewport.setGridVisible(viewportState.isShowGrid());
        }
    }

    /**
     * Toggle axes visibility.
     */
    public void toggleAxes(OpenMason3DViewport viewport) {
        viewportState.toggleAxes();
        if (viewport != null) {
            viewport.setAxesVisible(viewportState.isShowAxes());
        }
    }

    /**
     * Toggle wireframe mode.
     */
    public void toggleWireframe(OpenMason3DViewport viewport) {
        viewportState.toggleWireframe();
        if (viewport != null) {
            viewport.setWireframeMode(viewportState.isWireframeMode());
        }
        statusService.updateStatus("Wireframe " + (viewportState.isWireframeMode() ? "enabled" : "disabled"));
    }

    /**
     * Toggle transform gizmo visibility.
     */
    public void toggleGizmo(OpenMason3DViewport viewport) {
        viewportState.toggleGizmo();
        if (viewport != null && viewport.getGizmoRenderer() != null) {
            viewport.getGizmoRenderer().getGizmoState().setEnabled(viewportState.isShowGizmo());
        }
        statusService.updateStatus("Transform gizmo " + (viewportState.isShowGizmo() ? "enabled" : "disabled"));
    }

    /**
     * Switch texture variant.
     */
    public void switchTextureVariant(OpenMason3DViewport viewport, TransformState transformState, String variantName) {
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
    public void changeRenderMode(OpenMason3DViewport viewport, ViewportState state) {
        String renderMode = state.getCurrentRenderMode();

        if (viewport != null) {
            switch (renderMode.toLowerCase()) {
                case "wireframe":
                    state.setWireframeMode(true);
                    viewport.setWireframeMode(true);
                    break;
                default:
                    state.setWireframeMode(false);
                    viewport.setWireframeMode(false);
                    break;
            }
            statusService.updateStatus("Render mode: " + renderMode);
        }
    }
}
