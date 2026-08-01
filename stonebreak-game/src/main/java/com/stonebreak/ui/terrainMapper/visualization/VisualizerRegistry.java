package com.stonebreak.ui.terrainMapper.visualization;

import com.stonebreak.ui.terrainMapper.visualization.impl.BiomeVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.HeightVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.TopographyVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.WaterVisualizer;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig;
import com.stonebreak.world.generation.diffusion.DiffusionTileCache;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.generation.diffusion.process.TerrainServiceProcessManager;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.water.NativeWaterTiles;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lazily binds every {@link VisualizerKind} to a concrete {@link NoiseVisualizer}
 * built around a seeded {@link DiffusionTileCache}. Rebuild with {@link #rebuild(long)}
 * when the seed changes; everything downstream (cache, renderer) reads through
 * this registry so a single rebuild swaps all channels atomically.
 */
public final class VisualizerRegistry {

    private final Map<VisualizerKind, NoiseVisualizer> visualizers = new EnumMap<>(VisualizerKind.class);
    private long seed;

    public VisualizerRegistry(long seed) {
        rebuild(seed);
    }

    public long seed() { return seed; }

    public NoiseVisualizer get(VisualizerKind kind) {
        return visualizers.get(kind);
    }

    /**
     * Starts (or restarts) the local terrain-diffusion services for the current seed, blocking
     * until they are healthy. Deliberately not called from {@link #rebuild} or the constructor:
     * this registry is built during game init, and booting a CUDA model server there would cost
     * every launch ~a minute for a screen the player may never open.
     *
     * <p>Called instead by {@code TerrainPreviewLoader}'s worker thread as the first step of
     * each sampling job, so the services come up when the mapper is actually shown without the
     * ~minute-long boot stalling the render thread. Never call it from the render path. It is a
     * cheap no-op once they are running for this seed.
     */
    public void ensureServices() {
        TerrainServiceProcessManager.getInstance().ensureRunningForSeed(seed);
    }

    /** Rebuild every visualizer against a fresh seed. Does not touch the services — see {@link #ensureServices()}. */
    public void rebuild(long newSeed) {
        this.seed = newSeed;
        DiffusionBridgeConfig config = DiffusionBridgeConfig.fromSystemProperties();
        // Same tile chain the world generator uses (TerrainGenerationSystem
        // .productionTileSource): with the native water backend the preview
        // must show the kernel-derived rivers/lakes, not the raw sea-level
        // plane the bridge serves when its hydrology is off.
        TerrainTileSource tileCache = new DiffusionTileCache(config, newSeed);
        if (NativeWaterTiles.nativeBackendSelected()) {
            tileCache = new NativeWaterTiles(tileCache, newSeed, config.tileSizeBlocks(), config.maxCachedTiles());
        }
        HeightMapGenerator heightMap = new HeightMapGenerator(tileCache);
        BiomeManager biomes = new BiomeManager(tileCache);

        // HEIGHT, TOPOGRAPHY and WATER deliberately share one HeightMapGenerator: they are
        // renderings of the same resolved tile, so a second generator would only double the
        // tile traffic to the bridge for identical data.
        visualizers.put(VisualizerKind.HEIGHT, new HeightVisualizer(heightMap));
        visualizers.put(VisualizerKind.TOPOGRAPHY, new TopographyVisualizer(heightMap));
        visualizers.put(VisualizerKind.BIOME, new BiomeVisualizer(biomes));
        visualizers.put(VisualizerKind.WATER, new WaterVisualizer(heightMap));
    }
}
