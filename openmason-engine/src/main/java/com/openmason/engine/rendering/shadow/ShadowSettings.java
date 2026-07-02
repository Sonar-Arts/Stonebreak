package com.openmason.engine.rendering.shadow;

/**
 * Tunables for the cascaded sun-shadow system.
 *
 * <p>The cascade count is fixed at {@link #CASCADE_COUNT} because the GLSL sampling
 * snippet ({@link ShadowGlsl}) hard-codes its uniform array sizes and selection
 * logic to three cascades. Everything else — map resolution, shadow distance,
 * split placement, strength, PCF radius — is data. The {@link #low}, {@link #medium}
 * and {@link #high} presets bundle the resolution/filter trade-offs into the
 * quality tiers a host exposes to users.
 */
public final class ShadowSettings {

    /** Number of cascades. Must match the arrays and selection logic in {@link ShadowGlsl}. */
    public static final int CASCADE_COUNT = 3;

    private final int resolution;
    private final float maxDistance;
    private final float[] splitFractions;
    private final float strength;
    private final float casterBackup;
    private final int pcfRadius;

    /**
     * @param resolution     per-cascade depth-map resolution (e.g. 2048)
     * @param maxDistance    view-space distance beyond which shadows fade out entirely
     * @param splitFractions fraction of {@code maxDistance} where each cascade ends,
     *                       ascending, length {@link #CASCADE_COUNT}, last entry 1.0
     * @param strength       0..1 how dark a fully shadowed surface gets (1 = black diffuse)
     * @param casterBackup   extra world units the light volume extends toward the sun so
     *                       casters outside the camera frustum (tall terrain, trees) still
     *                       land in the map
     * @param pcfRadius      receiver-side PCF kernel radius in texels: 0 = hardware 2x2
     *                       only, 1 = 3x3 taps, 2 = 5x5 taps (clamped to [0, 2])
     */
    public ShadowSettings(int resolution, float maxDistance, float[] splitFractions,
                          float strength, float casterBackup, int pcfRadius) {
        if (splitFractions.length != CASCADE_COUNT) {
            throw new IllegalArgumentException("splitFractions must have length " + CASCADE_COUNT);
        }
        this.resolution = resolution;
        this.maxDistance = maxDistance;
        this.splitFractions = splitFractions.clone();
        this.strength = strength;
        this.casterBackup = casterBackup;
        this.pcfRadius = Math.max(0, Math.min(2, pcfRadius));
    }

    private static final float[] DEFAULT_SPLITS = {0.12f, 0.34f, 1.0f};

    /** Fast preset: 1024px maps (~12.6 MB VRAM), hardware-filtered edges only. */
    public static ShadowSettings low(float maxDistance) {
        return new ShadowSettings(1024, maxDistance, DEFAULT_SPLITS, 0.55f, 64.0f, 0);
    }

    /** Balanced preset: 2048px maps (~50 MB VRAM), 3x3 PCF penumbra. */
    public static ShadowSettings medium(float maxDistance) {
        return new ShadowSettings(2048, maxDistance, DEFAULT_SPLITS, 0.55f, 64.0f, 1);
    }

    /** Crisp preset: 4096px maps (~201 MB VRAM), 5x5 PCF penumbra. */
    public static ShadowSettings high(float maxDistance) {
        return new ShadowSettings(4096, maxDistance, DEFAULT_SPLITS, 0.55f, 64.0f, 2);
    }

    /** Balanced defaults: the {@link #medium} preset with a 100-block reach. */
    public static ShadowSettings defaults() {
        return medium(100.0f);
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

    /** PCF kernel radius in texels (0 = hardware 2x2 only, 1 = 3x3, 2 = 5x5). */
    public int pcfRadius() {
        return pcfRadius;
    }
}
