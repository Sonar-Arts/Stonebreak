package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.generation.ClimateLayout;
import com.stonebreak.world.generation.HeightCurveVersion;
import com.stonebreak.world.generation.NativeCubeGenerator.HeightCurveSpec;
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
 * {@link HeightCurveVersion} x {@link ClimateLayout} x {@link TerrainScalePreset}). The constants here are the 1:4
 * preset's v1 curve (the Standard generator's); a DaedalusTGM-Exp world's curve is
 * {@link TerrainScalePreset#heightCurve} of its saved {@link HeightCurveVersion} (v2 adds a shelf band near sea level).
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
        return worldConfigJson(scale, ClimateLayout.DEFAULT);
    }

    /**
     * {@link #worldConfigJson(TerrainScalePreset)} for a world with the EXP-2 climate {@code layout}: adds
     * {@code climate_layout} (always explicit, even for {@link ClimateLayout#BELTS}, so the service's own default
     * - which an environment variable can change - never decides a saved world's climate).
     */
    public static String worldConfigJson(TerrainScalePreset scale, ClimateLayout layout) {
        return worldConfigJson(scale, layout, null);
    }

    /**
     * {@link #worldConfigJson(TerrainScalePreset, ClimateLayout)} for a world created with the height curve
     * {@code curve} (null = {@link HeightCurveVersion#CURRENT}): adds {@code curve_version} and the shelf band
     * ({@code shelf_m_per_block} 0 for v1; the service leaves v1 worlds' fingerprints - their tile caches - as they
     * were before curve versions existed).
     */
    public static String worldConfigJson(TerrainScalePreset scale, ClimateLayout layout, HeightCurveVersion curve) {
        TerrainScalePreset s = scale == null ? TerrainScalePreset.ONE_TO_FOUR : scale;
        ClimateLayout l = ClimateLayout.orDefault(layout);
        HeightCurveSpec c = s.heightCurve(HeightCurveVersion.orCurrent(curve));
        double[] rates = c.rates();
        double[] knots = c.knots();
        Map<String, Object> world = new LinkedHashMap<>();
        // The cube world spans [MIN_Y, MAX_Y): the service clamps heights to [min_y, world_height).
        world.put("world_height", WorldConfiguration.MAX_Y);
        world.put("min_y", WorldConfiguration.MIN_Y);
        world.put("sea_level", WorldConfiguration.SEA_LEVEL);
        world.put("horizontal_m", s.horizontalMetresPerBlock());
        world.put("downscale", downscaleValue(s));
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
        world.put("climate_layout", l.value());
        world.put("curve_version", c.version().number());
        world.put("shelf_m_per_block", c.shelfMetresPerBlock());
        world.put("shelf_base_m", c.shelfBaseM());
        world.put("shelf_blend_m", c.shelfBlendM());
        world.put("shallow_m_per_block", c.shallowMetresPerBlock());
        world.put("shallow_base_m", c.shallowBaseM());
        world.put("shallow_blend_m", c.shallowBlendM());
        StringBuilder json = new StringBuilder("{");
        world.forEach((key, value) -> {
            json.append('"').append(key).append("\":");
            if (value instanceof String text) {
                json.append('"').append(text).append('"'); // enum values only: nothing to escape
            } else {
                json.append(value);
            }
            json.append(',');
        });
        json.setLength(json.length() - 1);
        return json.append('}').toString();
    }

    /**
     * Whole model pixels per block along each axis at {@code scale} (2 at 1:4, 1 at 1:2); 0 at 1:1, whose 15 m blocks
     * are finer than a pixel (see {@link #pixelsPerBlock}).
     */
    public static int downscale(TerrainScalePreset scale) {
        return (int) Math.floor(pixelsPerBlock(scale));
    }

    /** Model pixels per block along each axis at {@code scale}: 2 at 1:4, 1 at 1:2, 0.5 at 1:1 (two blocks a pixel). */
    public static double pixelsPerBlock(TerrainScalePreset scale) {
        return scale.horizontalMetresPerBlock() / MODEL_METERS_PER_PIXEL;
    }

    /**
     * The handshake's {@code downscale}: {@link #pixelsPerBlock} as an integer when whole (1:4 / 1:2: unchanged JSON,
     * so their world fingerprints - tile caches - keep), else the fraction (1:1: {@code 0.5}).
     */
    static Number downscaleValue(TerrainScalePreset scale) {
        double px = pixelsPerBlock(scale);
        return px == Math.rint(px) ? (Number) (int) px : (Number) px;
    }

    /** {@link #worldIndex(ClimateLayout, TerrainScalePreset, HeightCurveVersion)} of a current-curve world. */
    public static int worldIndex(ClimateLayout layout, TerrainScalePreset scale) {
        return worldIndex(layout, scale, null);
    }

    /**
     * The index of the handshake world serving ({@code curve}, {@code layout}, {@code scale}):
     * {@code (curve.ordinal() * layouts + layout.ordinal()) * presets + scale.ordinal()} (null curve =
     * {@link HeightCurveVersion#CURRENT}). For {@link HeightCurveVersion#V1} it is the index from before curve
     * versions existed; with {@link ClimateLayout#BELTS} too it equals the preset ordinal (0 = 1:4, 1 = 1:2). A tile
     * request names its world by this index and the READY's {@code cache_namespaces} list is in the same order.
     */
    public static int worldIndex(ClimateLayout layout, TerrainScalePreset scale, HeightCurveVersion curve) {
        TerrainScalePreset s = scale == null ? TerrainScalePreset.ONE_TO_FOUR : scale;
        int c = HeightCurveVersion.orCurrent(curve).ordinal();
        return (c * ClimateLayout.values().length + ClimateLayout.orDefault(layout).ordinal())
                * TerrainScalePreset.values().length + s.ordinal();
    }

    /**
     * TGMPipe's handshake (protocol v2): one world config per (height curve, climate layout, terrain scale) the game
     * offers, at {@link #worldIndex} - curves outer, then layouts, {@link TerrainScalePreset}s inner, all in ordinal
     * order. A tile request names its world by that index, so one service serves 1:4 and 1:2 worlds of every layout
     * and curve (and the terrain mapper) side by side: the model's caches are in metres, only the block stages and
     * the climate differ.
     */
    public static String handshakeJson() {
        StringBuilder json = new StringBuilder("{\"worlds\":[");
        for (HeightCurveVersion c : HeightCurveVersion.values()) {
            for (ClimateLayout l : ClimateLayout.values()) {
                for (TerrainScalePreset p : TerrainScalePreset.values()) {
                    json.append(worldConfigJson(p, l, c)).append(',');
                }
            }
        }
        json.setLength(json.length() - 1);
        return json.append("]}").toString();
    }
}
