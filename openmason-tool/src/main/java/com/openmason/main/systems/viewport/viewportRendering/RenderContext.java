package com.openmason.main.systems.viewport.viewportRendering;

import com.openmason.main.systems.viewport.ViewportCamera;

/**
 * Open Mason viewport rendering context.
 * Extends the engine RenderContext to provide direct ViewportCamera access
 * for editor-specific features while remaining compatible with the engine API.
 */
public class RenderContext extends com.openmason.engine.rendering.api.RenderContext {

    private final ViewportCamera viewportCamera;

    /**
     * Create render context with viewport camera.
     * ViewportCamera implements IRenderCamera, so it bridges to the engine API.
     */
    public RenderContext(ViewportCamera viewportCamera) {
        super(viewportCamera); // ViewportCamera implements IRenderCamera
        this.viewportCamera = viewportCamera;
    }

    /**
     * Get the viewport camera (editor-specific, provides arcball/first-person modes).
     *
     * @return the viewport camera
     */
    @Override
    public ViewportCamera getCamera() { return viewportCamera; }
}
