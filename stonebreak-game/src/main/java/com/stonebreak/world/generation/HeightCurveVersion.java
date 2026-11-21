package com.stonebreak.world.generation;

/**
 * Which version of the metres -&gt; blocks height curve a DaedalusTGM-Exp (DIFFUSION) world was created with: fixed at
 * creation and saved with the world ({@code WorldData#getHeightCurve}), part of its identity like the seed, the
 * terrain scale and the climate layout. A saved world keeps its curve forever, so cubes generated after an update
 * never meet older cubes at a seam; new worlds get {@link #CURRENT}.
 *
 * <p>Each version is its own world in the TGMPipe handshake ({@code TerrainScale#worldIndex}), so tiles and every
 * cache built from them are kept apart. The numbers of a version at a given terrain scale come from
 * {@code NativeCubeGenerator.TerrainScalePreset#heightCurve}. Standard (noise) worlds carry the value but ignore it:
 * their native generator always uses the preset's {@code curveRates()/curveKnots()} (the v1 curve).
 */
public enum HeightCurveVersion {

    /** The original curve: the deep-ocean rate runs right up to the shore (coastal seas 2-5 blocks deep at 1:4) and
     *  the sea bed is clamped at -4 km. Worlds saved before 2026-11-19 (no {@code heightCurve} in world.json). */
    V1(1),
    /** 2026-11-19: a shelf band (finer metres per block from the shore down to ~-200 m: coastal seas tens of blocks
     *  deep with a visible floor), and the sea bed reaches the trenches. */
    V2(2);

    /** The curve of worlds saved without one (older saves). */
    public static final HeightCurveVersion LEGACY = V1;
    /** The curve new worlds get. */
    public static final HeightCurveVersion CURRENT = V2;

    private final int number;

    HeightCurveVersion(int number) {
        this.number = number;
    }

    /** The persisted / handshake number ({@code WorldConfig.curve_version}): 1, 2, ... */
    public int number() {
        return number;
    }

    /** {@code version}, or {@link #CURRENT} when null (callers that build a new terrain: previews, new worlds). */
    public static HeightCurveVersion orCurrent(HeightCurveVersion version) {
        return version == null ? CURRENT : version;
    }

    /** The version numbered {@code number}; null for an unknown number. */
    public static HeightCurveVersion of(int number) {
        for (HeightCurveVersion v : values()) {
            if (v.number == number) {
                return v;
            }
        }
        return null;
    }

    /**
     * A persisted value: the number ({@code 2}) or the constant name ({@code V2}); {@code fallback} when missing or
     * unrecognised (a save written before the field existed reads as {@link #LEGACY}).
     */
    public static HeightCurveVersion parse(String text, HeightCurveVersion fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String t = text.trim();
        try {
            HeightCurveVersion v = of(Integer.parseInt(t));
            return v != null ? v : fallback;
        } catch (NumberFormatException e) {
            for (HeightCurveVersion v : values()) {
                if (v.name().equalsIgnoreCase(t)) {
                    return v;
                }
            }
            return fallback;
        }
    }
}
