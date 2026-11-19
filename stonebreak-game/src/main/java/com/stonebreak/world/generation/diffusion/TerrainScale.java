package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
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
 * into blocks with exactly these numbers, handed over in its handshake ({@link #handshakeJson}, one config per
 * {@link TerrainScalePreset}). The constants here are the 1:4 preset's.
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

    /** {@link #CURVE_RATES}, copied: the distilled Standard generator's height curve uses the same knobs. */
    public static double[] curveRates() {
        return CURVE_RATES.clone();
    }

    /** {@link #CURVE_KNOTS}, copied. */
    public static double[] curveKnots() {
        return CURVE_KNOTS.clone();
    }

    /**
     * One world config as TGMPipe reads it, for {@code scale}: keys match
     * {@code terrain_slm.world.world_config.WorldConfig} exactly (the service rejects a missing or unknown one), so
     * the service never decides a world's scale on its own. Metres per block and the height curve come from the
     * preset (1:4 = 60 m blocks, 1:2 = 30 m blocks with half the curve rates); tile size and knots are shared.
     */
    public static String worldConfigJson(TerrainScalePreset scale) {
        TerrainScalePreset s = scale == null ? TerrainScalePreset.ONE_TO_FOUR : scale;
        double[] rates = s.curveRates();
        double[] knots = s.curveKnots();
        Map<String, Number> world = new LinkedHashMap<>();
        // The cube world spans [MIN_Y, MAX_Y): the service clamps heights to [min_y, world_height).
        world.put("world_height", WorldConfiguration.MAX_Y);
        world.put("min_y", WorldConfiguration.MIN_Y);
        world.put("sea_level", WorldConfiguration.SEA_LEVEL);
        world.put("horizontal_m", s.horizontalMetresPerBlock());
        world.put("downscale", downscale(s));
        world.put("tile_size", TILE_SIZE_BLOCKS);
        world.put("ocean_m_per_block", rates[0]);
        world.put("lowland_m_per_block", rates[1]);
        world.put("midland_m_per_block", rates[2]);
        world.put("highland_m_per_block", rates[3]);
        world.put("lowland_top_m", knots[0]);
        world.put("highland_base_m", knots[1]);
        world.put("shore_blend_m", knots[2]);
        world.put("midland_blend_m", knots[3]);
        world.put("highland_blend_m", knots[4]);
        StringBuilder json = new StringBuilder("{");
        world.forEach((key, value) -> json.append('"').append(key).append("\":").append(value).append(','));
        json.setLength(json.length() - 1);
        return json.append('}').toString();
    }

    /** Model pixels per block along each axis at {@code scale} (2 at 1:4, 1 at 1:2). */
    public static int downscale(TerrainScalePreset scale) {
        return (int) Math.round(scale.horizontalMetresPerBlock() / MODEL_METERS_PER_PIXEL);
    }

    /**
     * TGMPipe's handshake (protocol v2): one world config per terrain scale the game offers, in
     * {@link TerrainScalePreset} ordinal order. A tile request names its world by that ordinal, so one service
     * serves 1:4 and 1:2 worlds (and the terrain mapper at either scale) side by side: the model's caches are in
     * metres, only the block stages differ.
     */
    public static String handshakeJson() {
        StringBuilder json = new StringBuilder("{\"worlds\":[");
        for (TerrainScalePreset p : TerrainScalePreset.values()) {
            json.append(worldConfigJson(p)).append(',');
        }
        json.setLength(json.length() - 1);
        return json.append("]}").toString();
    }
}
