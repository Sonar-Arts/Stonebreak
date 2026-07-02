package com.openmason.engine.rendering.shadow;

/**
 * Tunables for the cascaded sun-shadow system.
 *
 * <p>The cascade count is fixed at {@link #CASCADE_COUNT} because the GLSL sampling
 * snippet ({@link ShadowGlsl}) hard-codes its uniform array sizes and selection
 * logic to three cascades. Everything else — map resolution, shadow distance,
 * split placement, strength — is data.
 */
public final class ShadowSettings {

    /** Number of cascades. Must match the arrays and selection logic in {@link ShadowGlsl}. */
    public static final int CASCADE_COUNT = 3;

    private final int resolution;
    private final float maxDistance;
    private final float[] splitFractions;
    private final float strength;
    private final float casterBackup;

    /**
     * @param resolution     per-cascade depth-map resolution (e.g. 2048)
     * @param maxDistance    view-space distance beyond which shadows fade out entirely
     * @param splitFractions fraction of {@code maxDistance} where each cascade ends,
     *                       ascending, length {@link #CASCADE_COUNT}, last entry 1.0
     * @param strength       0..1 how dark a fully shadowed surface gets (1 = black diffuse)
     * @param casterBackup   extra world units the light volume extends toward the sun so
     *                       casters outside the camera frustum (tall terrain, trees) still
     *                       land in the map
     */
    public ShadowSettings(int resolution, float maxDistance, float[] splitFractions,
                          float strength, float casterBackup) {
        if (splitFractions.length != CASCADE_COUNT) {
            throw new IllegalArgumentException("splitFractions must have length " + CASCADE_COUNT);
        }
        this.resolution = resolution;
        this.maxDistance = maxDistance;
        this.splitFractions = splitFractions.clone();
        this.strength = strength;
        this.casterBackup = casterBackup;
    }

    /** Balanced defaults: 2048px, 100-block reach, soft (never fully black) shadows. */
    public static ShadowSettings defaults() {
        return new ShadowSettings(2048, 100.0f, new float[] {0.12f, 0.34f, 1.0f}, 0.55f, 64.0f);
    }

    public int resolution() {
        return resolution;
    }

    public float maxDistance() {
        return maxDistance;
    }

    /** View-space distance where cascade {@code i} ends. */
    public float splitFar(int i) {
        return maxDistance * splitFractions[i];
    }

    public float strength() {
        return strength;
    }

    public float casterBackup() {
        return casterBackup;
    }
}
