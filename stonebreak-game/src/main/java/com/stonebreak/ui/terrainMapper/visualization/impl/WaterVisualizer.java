package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Water overlay: renders the per-column water level the diffusion pipeline
 * derives (Phase 6-8 of the rivers-and-lakes plan) — sea, rivers and lakes
 * alike, since the ocean is one case of that field rather than a separate
 * rule. Dry columns render as a near-black background so the drainage
 * network reads clearly against it; wet columns are colored by their water
 * level so a lowland river or the sea reads differently from a mountain lake.
 *
 * <p>Unlike {@link TopographyVisualizer}, which colors the terrain height,
 * this colors {@link HeightMapGenerator#waterLevel} directly — the two are
 * independent per column (a column can be dry at any height), so this view
 * exists to show what the height ramp alone cannot: where the water actually
 * is.
 */
public final class WaterVisualizer implements NoiseVisualizer {

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;

    /** No water at all — background, chosen dark so wet columns pop. */
    private static final int DRY = 0xFF141414;
    /** Water at or below sea level. */
    private static final int WATER_LOW = 0xFF0D3B66;
    /** Water at {@link TerrainMapperConfig#TOPO_LAND_CEILING} above sea level or higher. */
    private static final int WATER_HIGH = 0xFF7FDBFF;

    private final HeightMapGenerator heightMap;

    public WaterVisualizer(HeightMapGenerator heightMap) {
        this.heightMap = heightMap;
    }

    @Override public String displayName() { return "Water"; }

    @Override
    public float sample(int worldX, int worldZ) {
        return heightMap.waterLevel(worldX, worldZ);
    }

    @Override
    public float normalize(float raw) {
        // Pass-through so colorFor sees the real water level (or NO_WATER) —
        // same trick TopographyVisualizer uses for block heights.
        return raw;
    }

    @Override
    public int colorFor(float waterLevelRaw) {
        int waterLevel = Math.round(waterLevelRaw);
        if (waterLevel == TerrainTile.NO_WATER) {
            return DRY;
        }
        float span = Math.max(1f, TerrainMapperConfig.TOPO_LAND_CEILING);
        float t = clamp01((waterLevel - SEA_LEVEL) / span);
        return argbLerp(WATER_LOW, WATER_HIGH, t);
    }

    @Override
    public String formatValue(float raw) {
        int waterLevel = Math.round(raw);
        return (waterLevel == TerrainTile.NO_WATER) ? "dry" : (waterLevel + " blocks");
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
