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
import java.util.List;
import java.util.Map;

/**
 * Standard Generation's modes, read straight off a seeded {@link NoiseRouter}. Local and
 * instant, so nothing is cached and there are no services to start.
 */
final class StandardPreview implements GeneratorPreview {

    private static final List<VisualizerKind> MODES = List.of(
            VisualizerKind.HEIGHT, VisualizerKind.CONTINENTALNESS, VisualizerKind.EROSION,
            VisualizerKind.PEAKS_VALLEYS, VisualizerKind.TEMPERATURE, VisualizerKind.MOISTURE,
            VisualizerKind.BIOME);

    @Override
    public List<VisualizerKind> modes() {
        return MODES;
    }

    @Override
    public Built build(long seed, PreviewSampleStore store) {
        NoiseRouter router = new NoiseRouter(seed);
        HeightMapGenerator heightMap = new HeightMapGenerator(router);
        BiomeManager biomes = new BiomeManager(router, heightMap);

        Map<VisualizerKind, NoiseVisualizer> visualizers = new EnumMap<>(VisualizerKind.class);
        visualizers.put(VisualizerKind.HEIGHT, new HeightVisualizer(heightMap));
        visualizers.put(VisualizerKind.CONTINENTALNESS, new ContinentalnessVisualizer(router));
        visualizers.put(VisualizerKind.EROSION, new ErosionVisualizer(router));
        visualizers.put(VisualizerKind.PEAKS_VALLEYS, new PeaksValleysVisualizer(router));
        visualizers.put(VisualizerKind.TEMPERATURE, new TemperatureVisualizer(router, heightMap));
        visualizers.put(VisualizerKind.MOISTURE, new MoistureVisualizer(router));
        visualizers.put(VisualizerKind.BIOME, new BiomeVisualizer(biomes));
        return new Built(visualizers, null, null);
    }
}
