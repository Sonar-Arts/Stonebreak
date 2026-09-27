package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.operations.WorldConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The world's scale against real terrain: the one place that decides how many metres a
 * block stands for, horizontally and vertically.
 *
 * <p>The 1:1 reference is the diffusion-era mapping: 15 m blocks, and the bridge height curve
 * at 12 / 4 / 10 / 24 metres per block (ocean / lowland / midland / highland). The world runs
 * at {@link #WORLD_SCALE_DIVISOR}:1 of that on both axes -- 60 m blocks from the 30 m terrain
 * model, and every curve band 4x coarser -- so relief keeps its proportions while 0..4800 m of
 * land fits between sea level (y 64) and y ~183 of a 256-block world.
 *
 * <p>The terrain model never sees any of this: it works in metres on its own 30 m grid.
 * Blocks are decided downstream by the bridge (and, for water, the model server), both of
 * which read the {@code TERRAIN_BRIDGE_*} values {@link #serviceEnvironment} produces.
 */
public final class TerrainScale {

    /** Metres per native pixel of the terrain model (Copernicus GLO-30). */
    public static final double MODEL_METERS_PER_PIXEL = 30.0;
    /** World scale against the 1:1 reference (15 m blocks): 4 means 1:4. */
    public static final int WORLD_SCALE_DIVISOR = 4;
    /** Metres of real terrain per block, horizontally. */
    public static final double HORIZONTAL_METERS_PER_BLOCK = 15.0 * WORLD_SCALE_DIVISOR;
    /** Model pixels averaged into one block along each axis. */
    public static final int DOWNSCALE = (int) Math.round(HORIZONTAL_METERS_PER_BLOCK / MODEL_METERS_PER_PIXEL);

    /** 1:1 height-curve rates, metres per block: ocean, lowland, midland, highland. */
    private static final double[] REFERENCE_CURVE_RATES = {12.0, 4.0, 10.0, 24.0};
    private static final String[] CURVE_RATE_KEYS = {
            "TERRAIN_BRIDGE_OCEAN_METERS_PER_BLOCK", "TERRAIN_BRIDGE_LOWLAND_METERS_PER_BLOCK",
            "TERRAIN_BRIDGE_MIDLAND_METERS_PER_BLOCK", "TERRAIN_BRIDGE_HIGHLAND_METERS_PER_BLOCK"};

    private TerrainScale() {}

    /**
     * Environment for the terrain services (bridge and model server alike), so both map
     * metres to blocks identically and agree with {@link WorldConfiguration}.
     *
     * @param downscaleCapable whether the model server understands {@code downscale}
     *        (terrain-slm does; the legacy diffusion upstream does not and keeps 15 m blocks)
     */
    public static Map<String, String> serviceEnvironment(boolean downscaleCapable) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TERRAIN_BRIDGE_WORLD_HEIGHT", String.valueOf(WorldConfiguration.WORLD_HEIGHT));
        env.put("TERRAIN_BRIDGE_SEA_LEVEL", String.valueOf(WorldConfiguration.SEA_LEVEL));
        for (int i = 0; i < CURVE_RATE_KEYS.length; i++) {
            env.put(CURVE_RATE_KEYS[i], format(REFERENCE_CURVE_RATES[i] * WORLD_SCALE_DIVISOR));
        }
        if (downscaleCapable) {
            env.put("TERRAIN_BRIDGE_SCALE", "1");
            env.put("TERRAIN_BRIDGE_DOWNSCALE", String.valueOf(DOWNSCALE));
            env.put("TERRAIN_BRIDGE_HORIZONTAL_METERS_PER_BLOCK", format(HORIZONTAL_METERS_PER_BLOCK));
            env.put("TERRAIN_BRIDGE_METERS_PER_BLOCK", format(HORIZONTAL_METERS_PER_BLOCK));
        }
        return env;
    }

    private static String format(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
