package com.openmason.engine.rendering.postfx;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Per-frame data the game supplies to post-processing effects.
 *
 * <p>Matrices are live references (the projection matrix is mutated in place on window
 * resize) — effects must read them during {@code apply()} and never cache copies.</p>
 *
 * @param viewMatrix       the camera view matrix for this frame
 * @param projectionMatrix the projection matrix for this frame
 * @param sunDirection     normalized world-space direction toward the sun
 * @param effectStrength   overall effect intensity in [0, 1], e.g. driven by time of day
 * @param cloudTexture     texture whose alpha is how much light the clouds let through at each
 *                         screen position (any resolution), or 0 when there are no clouds
 */
public record PostFxFrameParams(
        Matrix4f viewMatrix,
        Matrix4f projectionMatrix,
        Vector3f sunDirection,
        float effectStrength,
        int cloudTexture) {
}
