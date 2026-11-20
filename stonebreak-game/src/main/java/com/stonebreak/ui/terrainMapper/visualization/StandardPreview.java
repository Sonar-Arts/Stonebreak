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
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Standard Generation's modes at the selected terrain scale. Height is the native generator's own column
 * height ({@link NativeStandardColumns}: exactly what a world created with this seed and scale gets); the
 * climate and shape fields (continentalness, erosion, peaks/valleys, temperature, moisture, biome) are read
 * off a seeded Java {@link NoiseRouter} - the same fields the native generator ports - at the native shape
 * space position, so they line up with the heights at 1:4 and 1:2 alike. Local and fast, so nothing is
 * cached; the native generator is the only resource, closed with the visualizers. Without the worldgen
 * library the Height mode falls back to the Java height map (1:4 reference curve).
 */
final class StandardPreview implements GeneratorPreview {

    private static final List<VisualizerKind> MODES = List.of(
            VisualizerKind.HEIGHT, VisualizerKind.CONTINENTALNESS, VisualizerKind.EROSION,
            VisualizerKind.PEAKS_VALLEYS, VisualizerKind.TEMPERATURE, VisualizerKind.MOISTURE,
            VisualizerKind.BIOME);

    private volatile TerrainScalePreset scale = TerrainScalePreset.ONE_TO_FOUR;

    /** The preset the next {@link #build} previews. */
    void setScale(TerrainScalePreset scale) {
        this.scale = scale == null ? TerrainScalePreset.ONE_TO_FOUR : scale;
    }

    TerrainScalePreset scale() {
        return scale;
    }

    @Override
    public List<VisualizerKind> modes() {
        return MODES;
    }

    @Override
    public Built build(long seed, PreviewSampleStore store) {
        TerrainScalePreset preset = scale;
        double s = NativeStandardColumns.shapeScale(preset);
        // a new world's preview: the current Standard shape (its native heights below are the same shape)
        NoiseRouter router = new NoiseRouter(seed, com.stonebreak.world.generation.StandardShapeVersion.CURRENT);
        HeightMapGenerator heightMap = new HeightMapGenerator(router);
        BiomeManager biomes = new BiomeManager(router, heightMap);
        NativeStandardColumns columns = NativeStandardColumns.open(seed, preset);

        HeightVisualizer.Heights heights = columns != null ? columns::height
            : (x, z) -> heightMap.generateHeight((int) Math.floor(x * s), (int) Math.floor(z * s));

        Map<VisualizerKind, NoiseVisualizer> visualizers = new EnumMap<>(VisualizerKind.class);
        visualizers.put(VisualizerKind.HEIGHT, new HeightVisualizer(heights));
        visualizers.put(VisualizerKind.CONTINENTALNESS, ShapeScaledVisualizer.of(new ContinentalnessVisualizer(router), s));
        visualizers.put(VisualizerKind.EROSION, ShapeScaledVisualizer.of(new ErosionVisualizer(router), s));
        visualizers.put(VisualizerKind.PEAKS_VALLEYS, ShapeScaledVisualizer.of(new PeaksValleysVisualizer(router), s));
        visualizers.put(VisualizerKind.TEMPERATURE,
            ShapeScaledVisualizer.of(new TemperatureVisualizer(router, heightMap), s));
        visualizers.put(VisualizerKind.MOISTURE, ShapeScaledVisualizer.of(new MoistureVisualizer(router), s));
        visualizers.put(VisualizerKind.BIOME, ShapeScaledVisualizer.of(new BiomeVisualizer(biomes), s));
        return new Built(visualizers, null, columns);
    }
}
