package com.stonebreak.world.generation;

/**
 * Which version of the Standard (noise) generator's distilled shape a {@link TerrainGeneratorType#STANDARD} world was
 * created with: fixed at creation and saved with the world (world.json {@code "standardShape"}), part of its identity
 * like the seed and the terrain scale. A saved world keeps its shape forever, so cubes generated after an update never
 * meet older cubes at a seam; new worlds get {@link #CURRENT}. Native: {@code sbw_gen_config.standard_shape}.
 *
 * <p>Separate from {@link HeightCurveVersion} and {@link WorldFeatureVersion} on purpose: Standard worlds created before
 * this field existed already carry those (they ignored them), so their values cannot select Standard terrain. DaedalusTGM
 * (DIFFUSION) worlds carry this value and ignore it.
 */
public enum StandardShapeVersion {

    /**
     * The 2026-11-01 distillation of DaedalusTGM-Exp v4 (DistilledShape {@code FITTED}): the original height curve, no
     * surface features. Worlds saved without {@code standardShape} in world.json.
     */
    V1(1, HeightCurveVersion.V1, false),
    /**
     * 2026-11-19: distilled from DaedalusTGM-Exp-2 ({@code exp2-r3}; no WorldClim lineage): refitted shape with lowland
     * landform provinces, dendritic tributaries and an open ocean with shelf and abyss ({@code FITTED_V2}), the
     * height-curve v2 sea bands, inland pits as lakes, and the DaedalusTGM worlds' surface features driven by distilled
     * channels: sea-floor materials, land-surface materials, boulders and vegetation density.
     */
    V2(2, HeightCurveVersion.V2, true);

    /** The shape of Standard worlds saved without one (older saves). */
    public static final StandardShapeVersion LEGACY = V1;
    /** The shape new Standard worlds get. */
    public static final StandardShapeVersion CURRENT = V2;

    private final int number;
    private final HeightCurveVersion curve;
    private final boolean surfaceFeatures;

    StandardShapeVersion(int number, HeightCurveVersion curve, boolean surfaceFeatures) {
        this.number = number;
        this.curve = curve;
        this.surfaceFeatures = surfaceFeatures;
    }

    /** The persisted / native / handshake number. */
    public int number() {
        return number;
    }

    /** The height curve this shape's blocks are laid out with (v2: the shallows / shelf / deep sea bands). */
    public HeightCurveVersion heightCurve() {
        return curve;
    }

    /**
     * Whether the world gets the channel-driven surface features (sea floor, land surface, boulders, vegetation): the
     * native generator distils the channels from the shape itself (shape.cpp {@code standardChannels}).
     */
    public boolean surfaceFeatures() {
        return surfaceFeatures;
    }

    /** The rock shape bank the world's boulders read (0 = none). Fixed per version like the bank of a feature set. */
    public int rockBank() {
        return surfaceFeatures ? 1 : 0;
    }

    /** {@code version}, or {@link #CURRENT} when null. */
    public static StandardShapeVersion orCurrent(StandardShapeVersion version) {
        return version == null ? CURRENT : version;
    }

    /** The version numbered {@code number}; null for an unknown number. */
    public static StandardShapeVersion of(int number) {
        for (StandardShapeVersion v : values()) {
            if (v.number == number) {
                return v;
            }
        }
        return null;
    }

    /** A persisted number; {@code fallback} when missing (negative) or unknown. */
    public static StandardShapeVersion of(long number, StandardShapeVersion fallback) {
        if (number < 0 || number > Integer.MAX_VALUE) {
            return fallback;
        }
        StandardShapeVersion v = of((int) number);
        return v != null ? v : fallback;
    }
}
