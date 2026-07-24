package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Topographic map: surface height rendered as an elevation-banded color ramp with contour
 * lines, the way a paper topo map reads.
 *
 * <p>The ramp is <em>absolute</em>, not fitted to what happens to be on screen — a given
 * color always means the same block height no matter where you pan, which is the whole point
 * of a topographic view. Two independent ramps meet at
 * {@link WorldConfiguration#SEA_LEVEL} with a deliberate hard edge (indigo → light blue below,
 * green → white above); every other transition is a smooth lerp, so the shoreline is the only
 * place the color changes discontinuously.
 *
 * <p>Contours are drawn in {@link #postProcess} rather than {@link #colorFor(float)} because a
 * contour is a property of the boundary <em>between</em> samples, not of a sample.
 */
public final class TopographyVisualizer implements NoiseVisualizer {

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;

    // ─────────────────────────────────────────────── Palette
    // Below sea level: shallow water is a light blue that darkens into the abyss.
    private static final int WATER_DEEP    = 0xFF2E1A66; // indigo, y = 0
    private static final int WATER_SHALLOW = 0xFF7FC7E8; // light blue, y = SEA_LEVEL

    // Above sea level, evenly spaced across SEA_LEVEL..TOPO_LAND_CEILING.
    private static final int[] LAND_STOPS = {
            0xFF2E8B2E, // green  — lowlands, immediately above the waterline
            0xFFEDE04A, // yellow
            0xFFE8912B, // orange
            0xFFC0392B, // red
            0xFF8C8C8C, // gray
            0xFFF5F5F5, // white  — peaks at TOPO_LAND_CEILING and above
    };

    // ─────────────────────────────────────────────── Contours
    /**
     * Minor intervals a contour is allowed to take, in blocks; the major interval is always
     * double the rung in use. Round, map-legend numbers only — an interval of 83 is technically
     * fine but nobody can read heights off it.
     *
     * <p>The ladder stops at 50 (so the bold line stops at 100) rather than continuing to double
     * with the zoom: usable relief only runs from SEA_LEVEL to about
     * {@link TerrainMapperConfig#TOPO_LAND_CEILING}, and past a 100-block bold interval the whole
     * world carries a handful of lines and the view stops reading as a topo map at all. At
     * ZOOM_MIN the lines are therefore still denser than the ideal spacing — deliberately, since
     * the alternative is a blank map.
     */
    private static final int[] INTERVAL_LADDER = {5, 10, 25, 50};
    /** Not pure black: the major line has to be able to read as darker than this one. */
    private static final int MINOR_COLOR = 0xFF2A2A2A;
    private static final int MAJOR_COLOR = 0xFF000000;

    private final HeightMapGenerator heightMap;

    public TopographyVisualizer(HeightMapGenerator heightMap) {
        this.heightMap = heightMap;
    }

    @Override public String displayName() { return "Topography"; }

    @Override
    public float sample(int worldX, int worldZ) {
        return heightMap.generateHeight(worldX, worldZ);
    }

    @Override
    public float normalize(float raw) {
        // Pass-through so colorFor sees the real block height — the ramp is defined in
        // block-space, not in [0,1]. Same trick BiomeVisualizer uses for ordinals.
        return raw;
    }

    @Override
    public int colorFor(float blockHeight) {
        int y = Math.round(blockHeight);
        if (y <= SEA_LEVEL) {
            float t = clamp01(y / (float) SEA_LEVEL);
            return argbLerp(WATER_DEEP, WATER_SHALLOW, t);
        }
        float span = Math.max(1f, TerrainMapperConfig.TOPO_LAND_CEILING - SEA_LEVEL);
        return rampAt(LAND_STOPS, clamp01((y - SEA_LEVEL) / span));
    }

    @Override
    public String formatValue(float raw) {
        return Math.round(raw) + " blocks";
    }

    /**
     * Overlays contour lines at an interval chosen for the current zoom (see
     * {@link #minorIntervalFor}). Runs as two full passes rather than one: every major boundary
     * is also a minor boundary (the major interval is a multiple of the minor one), so painting
     * minors first and letting majors overwrite them gives the right precedence without tracking
     * a per-pixel mask. Both passes read only {@code raw}, never the pixels they are writing, so
     * a line already painted can never be mistaken for terrain by the next sample over.
     *
     * <p>The interval is derived from {@code blocksPerSample} alone, never from {@code raw}:
     * TerrainPreviewSampler re-runs this over a growing prefix of the same buffers and never
     * repaints already-published rows, so an interval that shifted as more terrain arrived would
     * leave the earlier rows' lines stranded on top of the new ones.
     */
    @Override
    public void postProcess(float[] raw, int[] pixels, int width, int height, float blocksPerSample) {
        int minorInterval = minorIntervalFor(blocksPerSample);
        int majorInterval = minorInterval * 2;
        // Bold majors are two samples wide; that only reads as weight while the lines are
        // comfortably apart. Once the interval has hit the top of the ladder and the spacing is
        // out of our hands, a hairline is the difference between a map and a black smear.
        boolean boldMajors = minorInterval < INTERVAL_LADDER[INTERVAL_LADDER.length - 1];

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (crossesBand(raw, width, row, x, y, minorInterval)) {
                    pixels[row + x] = MINOR_COLOR;
                }
            }
        }
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                if (!crossesBand(raw, width, row, x, y, majorInterval)) continue;
                pixels[row + x] = MAJOR_COLOR;
                if (!boldMajors) continue;
                // "Bold": two samples wide on both axes, so the line reads heavy whichever
                // way it happens to run across the grid.
                if (x + 1 < width) pixels[row + x + 1] = MAJOR_COLOR;
                if (y + 1 < height) pixels[row + width + x] = MAJOR_COLOR;
            }
        }
    }

    /**
     * Coarsest ladder rung the zoom calls for. Lines land roughly
     * {@code interval / (slope * blocksPerSample)} samples apart, so the interval has to grow
     * with the sample cell to hold that spacing — see
     * {@link TerrainMapperConfig#TOPO_CONTOUR_INTERVAL_SCALE}.
     */
    static int minorIntervalFor(float blocksPerSample) {
        float wanted = Math.max(1f, blocksPerSample) * TerrainMapperConfig.TOPO_CONTOUR_INTERVAL_SCALE;
        for (int rung : INTERVAL_LADDER) {
            if (rung >= wanted) return rung;
        }
        return INTERVAL_LADDER[INTERVAL_LADDER.length - 1];
    }

    /** True if this sample sits on the far side of an {@code interval}-block boundary from its west or north neighbour. */
    private static boolean crossesBand(float[] raw, int width, int row, int x, int y, int interval) {
        int here = band(raw[row + x], interval);
        if (x > 0 && band(raw[row + x - 1], interval) != here) return true;
        return y > 0 && band(raw[row - width + x], interval) != here;
    }

    private static int band(float blockHeight, int interval) {
        return Math.floorDiv(Math.round(blockHeight), interval);
    }

    /** Position {@code t} along an evenly spaced stop table. */
    private static int rampAt(int[] stops, float t) {
        float scaled = t * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) scaled);
        return argbLerp(stops[i], stops[i + 1], scaled - i);
    }

    private static int argbLerp(int a, int b, float t) {
        int r = lerpChannel(a >>> 16, b >>> 16, t);
        int g = lerpChannel(a >>> 8, b >>> 8, t);
        int bl = lerpChannel(a, b, t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int lerpChannel(int a, int b, float t) {
        int from = a & 0xFF;
        int to = b & 0xFF;
        return Math.round(from + (to - from) * t);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
