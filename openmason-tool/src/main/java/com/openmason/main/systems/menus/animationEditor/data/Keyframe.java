package com.openmason.main.systems.menus.animationEditor.data;

import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import org.joml.Vector3f;

/**
 * Immutable single-keyframe pose for a part.
 *
 * <p>Stores time (seconds, clip-relative), pose (position/rotation/scale —
 * pivot/origin lives on the part itself, not the keyframe), and the easing
 * applied between this keyframe and the next on the same track.
 *
 * @param time     Time in seconds, relative to the clip start
 * @param position Local-space translation
 * @param rotation Local-space Euler rotation in degrees
 * @param scale    Local-space scale factors
 * @param easing   Interpolation curve to the next keyframe
 */
public record Keyframe(
        float time,
        Vector3f position,
        Vector3f rotation,
        Vector3f scale,
        Easing easing
) {

    /**
     * Capture the current local transform of a part as a keyframe at the given time.
     */
    public static Keyframe fromPartTransform(float time, PartTransform t, Easing easing) {
        return new Keyframe(
                time,
                new Vector3f(t.position()),
                new Vector3f(t.rotation()),
                new Vector3f(t.scale()),
                easing != null ? easing : Easing.LINEAR
        );
    }

    /**
     * Build a {@link PartTransform} from this keyframe using the supplied origin.
     * Origin lives on the part, not the keyframe — animation does not move pivots.
     */
    public PartTransform toPartTransform(Vector3f origin) {
        return new PartTransform(
                new Vector3f(origin),
                new Vector3f(position),
                new Vector3f(rotation),
                new Vector3f(scale)
        );
    }

    public Keyframe withTime(float newTime) {
        return new Keyframe(newTime, position, rotation, scale, easing);
    }

    public Keyframe withPose(Vector3f newPos, Vector3f newRot, Vector3f newScale) {
        return new Keyframe(time, newPos, newRot, newScale, easing);
    }
}
