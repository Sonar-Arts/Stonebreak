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
 * the way Minecraft's mountains are: the height curve spends {@link #CURVE_RATES}
 * metres per block (ocean / lowland / midland / highland), so highlands stand ~1.6x and
 * lowlands ~3.75x taller than true scale. 2000 m lands at y ~170, 3000 m at ~197,
 * 4000 m at ~223 and 5000 m at ~250 of a 256-block world (sea level y 64); the terrain model
 * soft-caps summits below ~5150 m so nothing is sheared flat at the build limit.
 *
 * <p>The terrain model works in metres on its own 30 m grid; TGMPipe turns them
 * into blocks with exactly these numbers, handed over in its handshake ({@link #worldConfigJson}).
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

    /** Blocks per tile side: the unit TGMPipe builds, caches and sends. */
    public static final int TILE_SIZE_BLOCKS = 256;

    /** Height-curve rates, metres per block: ocean, lowland, midland, highland. */
    static final double[] CURVE_RATES = {48.0, 16.0, 24.0, 38.0};
    /**
     * Height-curve knots, metres: top of the lowland band, base of the highland band, and the
     * half-widths of the shore / midland / highland rate blends.
     */
    static final double[] CURVE_KNOTS = {600.0, 2000.0, 60.0, 150.0, 300.0};

    private TerrainScale() {}

    /**
     * The world config TGMPipe is handed in its handshake, as JSON — keys match
     * {@code terrain_slm.world.world_config.WorldConfig} exactly (the service rejects a missing or
     * unknown one), so the service never decides the world's scale on its own.
     */
    public static String worldConfigJson() {
        Map<String, Number> world = new LinkedHashMap<>();
        world.put("world_height", WorldConfiguration.WORLD_HEIGHT);
        world.put("sea_level", WorldConfiguration.SEA_LEVEL);
        world.put("horizontal_m", HORIZONTAL_METERS_PER_BLOCK);
        world.put("downscale", DOWNSCALE);
        world.put("tile_size", TILE_SIZE_BLOCKS);
        world.put("ocean_m_per_block", CURVE_RATES[0]);
        world.put("lowland_m_per_block", CURVE_RATES[1]);
        world.put("midland_m_per_block", CURVE_RATES[2]);
        world.put("highland_m_per_block", CURVE_RATES[3]);
        world.put("lowland_top_m", CURVE_KNOTS[0]);
        world.put("highland_base_m", CURVE_KNOTS[1]);
        world.put("shore_blend_m", CURVE_KNOTS[2]);
        world.put("midland_blend_m", CURVE_KNOTS[3]);
        world.put("highland_blend_m", CURVE_KNOTS[4]);
        StringBuilder json = new StringBuilder("{\"world\":{");
        world.forEach((key, value) -> json.append('"').append(key).append("\":").append(value).append(','));
        json.setLength(json.length() - 1);
        return json.append("}}").toString();
    }
}
