package com.openmason.main.systems.viewport;

import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.menus.preferences.PreferencesManager;
import com.openmason.main.systems.viewport.state.EditModeManager;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles all viewport business logic and operations.
 */
public class ViewportActions {

    private static final Logger logger = LoggerFactory.getLogger(ViewportActions.class);

    private final ViewportController viewport;
    private final ViewportUIState state;
    private final PreferencesManager preferencesManager;

    /**
     * Constructor with dependency injection.
     */
    public ViewportActions(ViewportController viewport, ViewportUIState state, PreferencesManager preferencesManager) {
        this.viewport = viewport;
        this.state = state;
        this.preferencesManager = preferencesManager;
    }

    // ========== View Mode Operations ==========

    public void updateViewMode() {
        String viewMode = state.getCurrentViewMode();
        ViewportCamera viewportCamera = viewport.getCamera();

        switch (viewMode.toLowerCase()) {
            case "front" -> {
                viewportCamera.setYaw(0.0f);
                viewportCamera.setPitch(0.0f);
            }
            case "side" -> {
                viewportCamera.setYaw(90.0f);
                viewportCamera.setPitch(0.0f);
            }
            case "top" -> {
                viewportCamera.setYaw(0.0f);
                viewportCamera.setPitch(-90.0f);
            }
            case "bottom" -> {
                viewportCamera.setYaw(0.0f);
                viewportCamera.setPitch(90.0f);
            }
            case "isometric" -> {
                viewportCamera.setYaw(45.0f);
                viewportCamera.setPitch(-30.0f);
            }
            default -> logger.warn("Unknown view mode: {}", viewMode);
        }

        updateCameraStateFromViewport();
    }

    public void resetView() {
        logger.info("Resetting viewport view");
        viewport.getCamera().reset();
        updateCameraStateFromViewport();
        state.getCurrentViewModeIndex().set(0); // Reset to perspective
    }

    public void fitToView() {
        logger.info("Fitting view to model");
        viewport.getCamera().setYaw(45.0f);
        viewport.getCamera().setPitch(-30.0f);
        updateCameraStateFromViewport();
    }

    // ========== Render Mode Operations ==========

    public void updateRenderMode() {
        String renderMode = state.getCurrentRenderMode();

        switch (renderMode.toLowerCase()) {
            case "wireframe" -> {
                state.getWireframeMode().set(true);
                viewport.setWireframeMode(true);
            }
            default -> {
                state.getWireframeMode().set(false);
                viewport.setWireframeMode(false);
            }
        }
    }

    public void toggleWireframe() {
        boolean newMode = !state.getWireframeMode().get();
        state.getWireframeMode().set(newMode);
        logger.info("Wireframe mode: {}", newMode);

        viewport.setWireframeMode(newMode);

        // Update render mode combo to match
        if (newMode) {
            state.getCurrentRenderModeIndex().set(1); // Wireframe
        } else {
            state.getCurrentRenderModeIndex().set(0); // Solid
        }
    }

    // ========== Visibility Toggle Operations ==========

    public void toggleGrid() {
        boolean visible = state.getGridVisible().get();
        logger.info("Grid visibility: {}", visible);
        viewport.setShowGrid(visible);
    }

    public void toggleAxes() {
        boolean visible = state.getAxesVisible().get();
        logger.info("Axes visibility: {}", visible);
        viewport.setAxesVisible(visible);
    }

    public void toggleGridSnapping() {
        boolean enabled = state.getGridSnappingEnabled().get();
        logger.info("Grid snapping: {}", enabled);
        viewport.setGridSnappingEnabled(enabled);

        // Persist to preferences
        preferencesManager.setGridSnappingEnabled(enabled);
    }

    public void toggleShowVertices() {
        boolean show = state.getShowVertices().get();
        viewport.setShowVertices(show);
    }

    // ========== Edge Operations ==========

    /**
     * Subdivide the currently hovered edge at its midpoint.
     * Only works in Edge edit mode.
     */
    public void subdivideHoveredEdge() {
        // Check if in Edge mode
        if (!EditModeManager.getInstance().isEdgeEditingAllowed()) {
            logger.debug("Edge subdivision requires Edge edit mode");
            return;
        }

        // Delegate to viewport controller
        int newVertexIndex = viewport.subdivideHoveredEdge();

        if (newVertexIndex >= 0) {
            logger.info("Edge subdivided, created vertex at index {}", newVertexIndex);
        }
    }

    // ========== Camera Operations ==========

    public void updateCameraMode() {
        String cameraMode = state.getCurrentCameraMode();
        ViewportCamera camera = viewport.getCamera();

        switch (cameraMode.toLowerCase()) {
            case "arcball" -> camera.setCameraMode(ViewportCamera.CameraMode.ARCBALL);
            case "first-person" -> camera.setCameraMode(ViewportCamera.CameraMode.FIRST_PERSON);
            default -> logger.warn("Unknown camera mode: {}", cameraMode);
        }
    }

    public void updateCameraDistance() {
        float distance = state.getCameraDistance().get();
        logger.debug("Camera distance updated: {}", distance);
        viewport.getCamera().setDistance(distance);
    }

    public void updateCameraPitch() {
        float pitch = state.getCameraPitch().get();
        logger.debug("Camera pitch updated: {}", pitch);
        viewport.getCamera().setPitch(pitch);
    }

    public void updateCameraYaw() {
        float yaw = state.getCameraYaw().get();
        logger.debug("Camera yaw updated: {}", yaw);
        viewport.getCamera().setYaw(yaw);
    }

    public void updateCameraFOV() {
        float fov = state.getCameraFOV().get();
        logger.debug("Camera FOV updated: {}", fov);
        viewport.getCamera().setFov(fov);
    }

    public void resetCamera() {
        logger.info("Resetting camera to defaults");
        state.resetCameraState();
        viewport.getCamera().reset();
    }

    public void updateCameraStateFromViewport() {
        // Sync UI state with actual camera state
        ViewportCamera viewportCamera = viewport.getCamera();
        state.updateCameraState(
            viewportCamera.getDistance(),
            viewportCamera.getPitch(),
            viewportCamera.getYaw(),
            viewportCamera.getFov()
        );
    }

    // ========== Transform Gizmo Operations ==========

    public void toggleGizmo() {
        boolean currentState = viewport.isGizmoEnabled();
        viewport.setGizmoEnabled(!currentState);
        logger.info("Transform gizmo toggled: {}", !currentState);
    }

    public void setGizmoMode(GizmoState.Mode mode) {
        viewport.setGizmoMode(mode);
        logger.info("Switched to {} mode", mode);
    }

    public void toggleUniformScaling() {
        boolean currentState = viewport.getGizmoUniformScaling();
        viewport.setGizmoUniformScaling(!currentState);
        logger.info("Uniform scaling toggled: {}", !currentState);
    }

    // ========== Rendering Settings ==========

    public void applyRenderingSettings() {
        logger.info("Applying rendering settings");
        // Future implementation for applying rendering quality settings
    }
}
