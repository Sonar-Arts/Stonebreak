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
 * @param sunColor         sunlight's colour (brightest channel 1), e.g. reddening as the sun sets
 * @param effectStrength   overall effect intensity in [0, 1], e.g. driven by time of day
 * @param skyTransmittanceTexture texture whose alpha is how much of the sky's light reaches the
 *                         eye at each screen position through what lies in front of it (cloud,
 *                         rain), at any resolution; 0 when nothing does
 */
public record PostFxFrameParams(
        Matrix4f viewMatrix,
        Matrix4f projectionMatrix,
        Vector3f sunDirection,
        Vector3f sunColor,
        float effectStrength,
        int skyTransmittanceTexture) {
}
