package com.openmason.engine.format.oma;

import org.joml.Vector3f;

/**
 * A single keyframe within an animation track.
 *
 * <p>Transforms are stored as the part's local pose at {@code time} seconds:
 * {@code position} and {@code scale} are absolute local values, {@code rotation}
 * is Euler degrees (XYZ order). {@code easing} names the interpolation curve
 * leading <em>into</em> the next keyframe; only {@code LINEAR} is honoured at
 * sample time and any other value falls back to linear.
 *
 * @param time     keyframe time in seconds
 * @param position local translation
 * @param rotation local Euler rotation in degrees
 * @param scale    local scale
 * @param easing   easing curve name (e.g. {@code "LINEAR"})
 */
public record ParsedKeyframe(
        float time,
        Vector3f position,
        Vector3f rotation,
        Vector3f scale,
        String easing
) {
    public ParsedKeyframe {
        if (position == null) position = new Vector3f();
        if (rotation == null) rotation = new Vector3f();
        if (scale == null) scale = new Vector3f(1f, 1f, 1f);
        if (easing == null || easing.isBlank()) easing = "LINEAR";
    }
}
