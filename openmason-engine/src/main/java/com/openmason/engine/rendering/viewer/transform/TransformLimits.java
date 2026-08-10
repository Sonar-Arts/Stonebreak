package com.openmason.engine.rendering.viewer.transform;

/**
 * Clamping policy applied by a {@link TransformState}.
 *
 * <p>These bounds were editor policy hardcoded into {@code TransformState}: the model
 * editor deliberately confines the single model being edited to the visible grid and to
 * a sane scale range, so the user cannot lose it off-screen. That policy is wrong for a
 * scene, where instances are placed across a whole layout, so the limits became a
 * parameter rather than a constant.
 *
 * @param positionExtent maximum absolute value for each position axis; positions are
 *                       clamped to {@code [-positionExtent, +positionExtent]}
 * @param minScale       minimum value for each scale axis
 * @param maxScale       maximum value for each scale axis
 */
public record TransformLimits(float positionExtent, float minScale, float maxScale) {

    /**
     * The model editor's historical limits: position confined to the ±10 grid, scale
     * clamped to [0.1, 3.0].
     *
     * <p>These values are load-bearing — they are what {@code TransformState}'s no-arg
     * constructor has always applied, and changing them changes model-editor behaviour.
     */
    public static final TransformLimits EDITOR_DEFAULT = new TransformLimits(10.0f, 0.1f, 3.0f);

    /**
     * No clamping at all.
     *
     * <p>Used by scene instances, which are positioned across an arbitrarily large
     * layout. Note this also permits negative scale, i.e. mirrored instances — the
     * editor default cannot express that because its minimum scale is positive.
     */
    public static final TransformLimits UNBOUNDED = new TransformLimits(
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);

    public TransformLimits {
        if (minScale > maxScale) {
            throw new IllegalArgumentException(
                    "minScale (" + minScale + ") must not exceed maxScale (" + maxScale + ")");
        }
        if (positionExtent < 0.0f) {
            throw new IllegalArgumentException("positionExtent must not be negative: " + positionExtent);
        }
    }

    /** Clamps one position axis into {@code [-positionExtent, +positionExtent]}. */
    public float clampPosition(float value) {
        return Math.max(-positionExtent, Math.min(positionExtent, value));
    }

    /** Clamps one scale axis into {@code [minScale, maxScale]}. */
    public float clampScale(float value) {
        return Math.max(minScale, Math.min(maxScale, value));
    }
}
