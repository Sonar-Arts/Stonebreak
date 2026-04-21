package com.stonebreak.ui.terrainMapper.visualization;

import com.stonebreak.ui.terrainMapper.visualization.impl.BiomeVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.ContinentalnessVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.ErosionVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.HeightVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.MoistureVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.PeaksValleysVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.TemperatureVisualizer;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.NoiseRouter;

import java.util.EnumMap;
import java.util.Map;

/**
 * Lazily binds every {@link VisualizerKind} to a concrete {@link NoiseVisualizer}
 * built around a seeded {@link NoiseRouter}. Rebuild with {@link #rebuild(long)}
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

    /** Rebuild every visualizer against a fresh seed. */
    public void rebuild(long newSeed) {
        this.seed = newSeed;
        NoiseRouter router = new NoiseRouter(newSeed);
        HeightMapGenerator heightMap = new HeightMapGenerator(router);
        BiomeManager biomes = new BiomeManager(router, heightMap);

        visualizers.put(VisualizerKind.HEIGHT, new HeightVisualizer(heightMap));
        visualizers.put(VisualizerKind.CONTINENTALNESS, new ContinentalnessVisualizer(router));
        visualizers.put(VisualizerKind.EROSION, new ErosionVisualizer(router));
        visualizers.put(VisualizerKind.PEAKS_VALLEYS, new PeaksValleysVisualizer(router));
        visualizers.put(VisualizerKind.TEMPERATURE, new TemperatureVisualizer(router, heightMap));
        visualizers.put(VisualizerKind.MOISTURE, new MoistureVisualizer(router));
        visualizers.put(VisualizerKind.BIOME, new BiomeVisualizer(biomes));
    }
}
