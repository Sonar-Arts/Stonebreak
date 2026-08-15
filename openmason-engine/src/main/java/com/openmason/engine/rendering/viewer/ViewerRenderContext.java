package com.openmason.engine.rendering.viewer;

import com.openmason.engine.rendering.viewer.camera.ViewerCamera;

/**
 * Open Mason viewport rendering context.
 * Extends the engine RenderContext to provide direct ViewerCamera access
 * for editor-specific features while remaining compatible with the engine API.
 */
public class ViewerRenderContext extends com.openmason.engine.rendering.api.RenderContext {

    private final ViewerCamera viewportCamera;

    /**
     * Create render context with viewport camera.
     * ViewerCamera implements IRenderCamera, so it bridges to the engine API.
     */
    public ViewerRenderContext(ViewerCamera viewportCamera) {
        super(viewportCamera); // ViewerCamera implements IRenderCamera
        this.viewportCamera = viewportCamera;
    }

    /**
     * Get the viewport camera (editor-specific, provides arcball/first-person modes).
     *
     * @return the viewport camera
     */
    @Override
    public ViewerCamera getCamera() { return viewportCamera; }
}
