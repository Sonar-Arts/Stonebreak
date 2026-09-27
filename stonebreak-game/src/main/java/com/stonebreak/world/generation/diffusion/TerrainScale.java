package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.operations.WorldConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The world's scale against real terrain: the one place that decides how many metres a
 * block stands for, horizontally and vertically.
 *
 * <p>Horizontally the world runs at 1:{@link #WORLD_SCALE_DIVISOR} of the diffusion-era 15 m
 * blocks: 60 m blocks from the 30 m terrain model. Vertically it is deliberately exaggerated,
 * the way Minecraft's mountains are: the bridge height curve spends {@link #CURVE_RATES}
 * metres per block (ocean / lowland / midland / highland), so highlands stand ~1.6x and
 * lowlands ~3.75x taller than true scale. 2000 m lands at y ~170, 3000 m at ~197,
 * 4000 m at ~223 and 5000 m at ~250 of a 256-block world (sea level y 64); the terrain model
 * soft-caps summits below ~5150 m so nothing is sheared flat at the build limit.
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

    /** Height-curve rates, metres per block: ocean, lowland, midland, highland. */
    static final double[] CURVE_RATES = {48.0, 16.0, 24.0, 38.0};
    private static final String[] CURVE_RATE_KEYS = {
            "TERRAIN_BRIDGE_OCEAN_METERS_PER_BLOCK", "TERRAIN_BRIDGE_LOWLAND_METERS_PER_BLOCK",
            "TERRAIN_BRIDGE_MIDLAND_METERS_PER_BLOCK", "TERRAIN_BRIDGE_HIGHLAND_METERS_PER_BLOCK"};

    private TerrainScale() {}

    /**
     * Environment for the terrain services (bridge and model server alike), so both map
     * metres to blocks identically and agree with {@link WorldConfiguration}.
     *
     * @param downscaleCapable whether the model server understands {@code downscale}
     *        (DaedalusTGM-Exp does; the legacy diffusion upstream does not and keeps 15 m blocks)
     */
    public static Map<String, String> serviceEnvironment(boolean downscaleCapable) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TERRAIN_BRIDGE_WORLD_HEIGHT", String.valueOf(WorldConfiguration.WORLD_HEIGHT));
        env.put("TERRAIN_BRIDGE_SEA_LEVEL", String.valueOf(WorldConfiguration.SEA_LEVEL));
        for (int i = 0; i < CURVE_RATE_KEYS.length; i++) {
            env.put(CURVE_RATE_KEYS[i], format(CURVE_RATES[i]));
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
