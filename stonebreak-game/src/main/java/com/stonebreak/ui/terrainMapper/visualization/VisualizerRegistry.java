package com.stonebreak.ui.terrainMapper.visualization;

import com.stonebreak.ui.terrainMapper.visualization.impl.BiomeVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.HeightVisualizer;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig;
import com.stonebreak.world.generation.diffusion.DiffusionTileCache;
import com.stonebreak.world.generation.diffusion.process.TerrainServiceProcessManager;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;

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
     * every launch ~a minute for a screen the player may never open. The render path calls this
     * instead, so the services come up when the mapper is actually shown; it is a cheap no-op
     * once they are running for this seed.
     */
    public void ensureServices() {
        TerrainServiceProcessManager.getInstance().ensureRunningForSeed(seed);
    }

    /** Rebuild every visualizer against a fresh seed. Does not touch the services — see {@link #ensureServices()}. */
    public void rebuild(long newSeed) {
        this.seed = newSeed;
        DiffusionTileCache tileCache = new DiffusionTileCache(DiffusionBridgeConfig.fromSystemProperties(), newSeed);
        HeightMapGenerator heightMap = new HeightMapGenerator(tileCache);
        BiomeManager biomes = new BiomeManager(tileCache);

        visualizers.put(VisualizerKind.HEIGHT, new HeightVisualizer(heightMap));
        visualizers.put(VisualizerKind.BIOME, new BiomeVisualizer(biomes));
    }
}
