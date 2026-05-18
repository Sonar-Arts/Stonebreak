package com.openmason.engine.format.oma;

import org.joml.Vector3f;

/**
 * Pure keyframe sampling for {@link ParsedAnimTrack}s.
 *
 * <p>Stateless and side-effect free: given a track and a time it returns the
 * interpolated local pose. Only {@code LINEAR} easing is implemented; any other
 * easing name falls back to linear interpolation.
 */
public final class AnimSampler {

    private AnimSampler() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Interpolated local pose of a part at a given clip time.
     *
     * @param position local translation
     * @param rotationDeg local Euler rotation in degrees (XYZ)
     * @param scale local scale
     */
    public record PartPose(Vector3f position, Vector3f rotationDeg, Vector3f scale) {
        /** The identity pose: no translation, no rotation, unit scale. */
        public static PartPose identity() {
            return new PartPose(new Vector3f(), new Vector3f(), new Vector3f(1f, 1f, 1f));
        }
    }

    /**
     * Sample a track at {@code timeSeconds}.
     *
     * <p>An empty track yields {@link PartPose#identity()}. A time before the
     * first keyframe or after the last is clamped to that endpoint. Otherwise
     * the two bracketing keyframes are linearly interpolated.
     */
    public static PartPose sample(ParsedAnimTrack track, float timeSeconds) {
        if (track == null || track.isEmpty()) {
            return PartPose.identity();
        }
        var keyframes = track.keyframes();
        ParsedKeyframe first = keyframes.get(0);
        if (timeSeconds <= first.time() || keyframes.size() == 1) {
            return poseOf(first);
        }
        ParsedKeyframe last = keyframes.get(keyframes.size() - 1);
        if (timeSeconds >= last.time()) {
            return poseOf(last);
        }

        for (int i = 0; i < keyframes.size() - 1; i++) {
            ParsedKeyframe a = keyframes.get(i);
            ParsedKeyframe b = keyframes.get(i + 1);
            if (timeSeconds >= a.time() && timeSeconds <= b.time()) {
                float span = b.time() - a.time();
                float u = span <= 0f ? 0f : (timeSeconds - a.time()) / span;
                return new PartPose(
                        lerp(a.position(), b.position(), u),
                        lerp(a.rotation(), b.rotation(), u),
                        lerp(a.scale(), b.scale(), u)
                );
            }
        }
        return poseOf(last);
    }

    /**
     * Wrap an elapsed time into the clip's playback range.
     *
     * @param elapsed  seconds since playback started
     * @param duration clip duration in seconds
     * @param loop     whether the clip loops
     * @return looping clips wrap modulo {@code duration}; non-looping clips
     *         clamp to {@code duration}
     */
    public static float wrapTime(float elapsed, float duration, boolean loop) {
        if (duration <= 0f) return 0f;
        if (loop) {
            float t = elapsed % duration;
            return t < 0f ? t + duration : t;
        }
        return Math.min(Math.max(elapsed, 0f), duration);
    }

    private static PartPose poseOf(ParsedKeyframe kf) {
        return new PartPose(
                new Vector3f(kf.position()),
                new Vector3f(kf.rotation()),
                new Vector3f(kf.scale())
        );
    }

    private static Vector3f lerp(Vector3f a, Vector3f b, float u) {
        return new Vector3f(
                a.x + (b.x - a.x) * u,
                a.y + (b.y - a.y) * u,
                a.z + (b.z - a.z) * u
        );
    }
}
