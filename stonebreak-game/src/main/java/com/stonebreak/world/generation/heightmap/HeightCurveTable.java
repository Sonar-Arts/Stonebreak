package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.StandardTerrain;
import com.stonebreak.world.generation.diffusion.TerrainScale;

/**
 * Elevation (metres) to block height: DaedalusTGM-Exp's height curve
 * ({@code terrain_slm/world/height_curve.py}), with the same knobs the game hands TGMPipe
 * ({@link TerrainScale#curveRates()}, {@link TerrainScale#curveKnots()}), so the distilled
 * Standard generator spends blocks on elevation exactly as the model's tiles do.
 *
 * <p>A metres-per-block <em>rate</em> blended between ocean / lowland / midland / highland bands
 * with quintic smoothsteps of finite support, integrated once (trapezoid, {@link #STEP_M} grid)
 * and anchored so 0 m lands on sea level. Lookups interpolate the table linearly and clamp at its
 * ends, like {@code np.interp}. The table is also handed to the native worldgen library
 * ({@link #table()}), which interpolates it with the same arithmetic — one table, two readers.
 */
public final class HeightCurveTable {

    /** First table elevation, metres (~y -19 under the game's curve: below block 0). */
    public static final double MIN_M = -4000.0;
    /** Last table elevation, metres. Above it the curve is above the build limit. */
    public static final double MAX_M = 7000.0;
    public static final double STEP_M = 1.0;

    private static final double[] TABLE = build();

    private HeightCurveTable() {
    }

    /** Unquantised block height at {@code elevationM} (floor it for the column height). */
    public static double blocks(double elevationM) {
        return interpolate(TABLE, elevationM);
    }

    /**
     * The shared lookup, exactly as {@code terrain_ctx.hpp} spells it: index = floor((e - MIN) /
     * STEP), linear between neighbours, clamped to the end values.
     */
    static double interpolate(double[] table, double elevationM) {
        double t = (elevationM - MIN_M) / STEP_M;
        if (t <= 0.0) {
            return table[0];
        }
        int last = table.length - 1;
        if (t >= last) {
            return table[last];
        }
        int i = (int) Math.floor(t);
        double f = t - i;
        return table[i] + f * (table[i + 1] - table[i]);
    }

    /** A copy of the integrated table (block height at MIN_M + k * STEP_M). */
    public static double[] table() {
        return TABLE.clone();
    }

    private static double[] build() {
        double[] rates = TerrainScale.curveRates();
        double[] knots = TerrainScale.curveKnots();
        double ocean = rates[0], lowland = rates[1], midland = rates[2], highland = rates[3];
        double lowlandTop = knots[0], highlandBase = knots[1];
        double shoreBlend = knots[2], midlandBlend = knots[3], highlandBlend = knots[4];

        int n = (int) Math.round((MAX_M - MIN_M) / STEP_M) + 1;
        double[] inv = new double[n];
        for (int k = 0; k < n; k++) {
            double e = MIN_M + k * STEP_M;
            double ramp = lowland + (midland - lowland) * (e - lowlandTop) / (highlandBase - lowlandTop);
            ramp = Math.max(Math.min(lowland, midland), Math.min(Math.max(lowland, midland), ramp));
            double m = blend(ocean, lowland, e, 0.0, shoreBlend);
            m = blend(m, ramp, e, lowlandTop, midlandBlend);
            m = blend(m, highland, e, highlandBase, highlandBlend);
            inv[k] = 1.0 / m;
        }
        double[] blocks = new double[n];
        for (int k = 1; k < n; k++) {
            blocks[k] = blocks[k - 1] + 0.5 * (inv[k] + inv[k - 1]) * STEP_M;
        }
        // Anchor: elevation 0 m sits exactly on sea level (0 m is a grid point).
        int zero = (int) Math.round(-MIN_M / STEP_M);
        double shift = StandardTerrain.SEA_LEVEL - blocks[zero];
        for (int k = 0; k < n; k++) {
            blocks[k] += shift;
        }
        if (blocks[0] > 0.0 || blocks[n - 1] < StandardTerrain.WORLD_HEIGHT - 1) {
            throw new IllegalStateException("height curve table does not span the block column: y "
                + blocks[0] + " .. " + blocks[n - 1]);
        }
        return blocks;
    }

    private static double blend(double below, double above, double e, double center, double halfWidth) {
        double t = (e - (center - halfWidth)) / (2.0 * halfWidth);
        t = Math.max(0.0, Math.min(1.0, t));
        double s = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
        return below + (above - below) * s;
    }
}
