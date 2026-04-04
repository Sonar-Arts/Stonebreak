package com.openmason.engine.rendering.api;

import org.joml.Matrix4f;

/**
 * Minimal camera interface for rendering.
 * Provides view and projection matrices needed by renderers.
 *
 * <p>Implementations:
 * <ul>
 *   <li>Open Mason: Wraps ViewportCamera (arcball/first-person)</li>
 *   <li>Stonebreak: Wraps game Camera + projection matrix</li>
 * </ul>
 */
public interface IRenderCamera {

    /**
     * Get the view matrix (camera transform).
     *
     * @return the current view matrix
     */
    Matrix4f getViewMatrix();

    /**
     * Get the projection matrix (perspective or orthographic).
     *
     * @return the current projection matrix
     */
    Matrix4f getProjectionMatrix();
}
