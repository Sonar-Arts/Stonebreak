package com.stonebreak.world.generation.diffusion.biomes;

import com.stonebreak.world.generation.HeightCurveVersion;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;

/**
 * How far a beach reaches from the ocean and how high it climbs, per world (agent BV, 2026-11-20).
 *
 * <p>{@link #LEGACY} is the rule every world had before: one column next to an ocean column, up to
 * {@code 8} blocks above the sea, neighbours in another 256-block tile ignored. Height-curve v2 worlds size it
 * physically instead: ~{@value #WIDTH_M} m wide (1 / 2 / 4 blocks at 1:4 / 1:2 / 1:1 - the 1:4 world's single
 * 60 m column) and no higher than ~{@value #MAX_RISE_M} m above the water (v2 land is 16 / 8 / 4 m per block,
 * so 2 / 4 / 8 blocks: the legacy 8 blocks put sand on 120 m bluffs at 1:4), reading neighbours across tile edges
 * (the legacy rule left a one-column notch in the sand wherever a coast crossed a tile edge). v1 worlds keep the
 * legacy rule so saved worlds generate new chunks exactly as before.
 *
 * @param reach      columns from an ocean column (Euclidean disc, radius^2 + radius) that can be beach
 * @param bandBlocks a column is beach only below sea level + this many blocks
 * @param crossTile  read ocean neighbours from the neighbouring tile across tile edges
 */
public record BeachRule(int reach, int bandBlocks, boolean crossTile) {

    /** The rule before 2026-11-20 (and of every height-curve v1 world). */
    public static final BeachRule LEGACY = new BeachRule(1, 8, false);

    /** Beach width of v2 worlds, metres. */
    public static final double WIDTH_M = 60.0;

    /** Highest ground (metres above the water) a v2 beach climbs to. */
    public static final double MAX_RISE_M = 30.0;

    public BeachRule {
        if (reach < 1 || bandBlocks < 1) {
            throw new IllegalArgumentException("beach reach / band must be >= 1");
        }
    }

    /** The beach rule of a DaedalusTGM-Exp world at {@code scale} created with {@code curve} (null = current). */
    public static BeachRule of(TerrainScalePreset scale, HeightCurveVersion curve) {
        if (scale == null || HeightCurveVersion.orCurrent(curve) == HeightCurveVersion.V1) {
            return LEGACY;
        }
        int reach = Math.max(1, (int) Math.round(WIDTH_M / scale.horizontalMetresPerBlock()));
        int band = Math.max(2, (int) Math.round(MAX_RISE_M / scale.curveRates()[1]));
        return new BeachRule(reach, band, true);
    }
}
