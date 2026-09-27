package com.stonebreak.ui.terrainMapper.visualization;

import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.visualization.impl.diffusion.BiomeVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.diffusion.HeightVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.diffusion.TopographyVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.diffusion.WaterVisualizer;
import com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig;
import com.stonebreak.world.generation.diffusion.DiffusionTileCache;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.generation.diffusion.biomes.BiomeManager;
import com.stonebreak.world.generation.diffusion.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.diffusion.process.TerrainServiceProcessManager;
import com.stonebreak.world.generation.water.BasinCache;
import com.stonebreak.world.generation.water.NativeWaterTiles;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Diffusion Generation's modes, read through the same tile chain the world generator uses.
 * Owns the terrain-diffusion services for the mapper: they start the first time a preview
 * needs them and stop when the player picks another generator.
 */
final class DiffusionPreview implements GeneratorPreview {

    private static final List<VisualizerKind> MODES = List.of(
            VisualizerKind.HEIGHT, VisualizerKind.TOPOGRAPHY, VisualizerKind.BIOME, VisualizerKind.WATER);

    /**
     * Serialises starting against stopping. A start that is already booting finishes before a
     * stop kills it, and a stop that wins the race leaves {@link #active} false so the start
     * that follows does nothing — either way the services end up off when the player has left.
     */
    private final Object serviceLock = new Object();
    private volatile boolean active;

    @Override
    public List<VisualizerKind> modes() {
        return MODES;
    }

    @Override
    public Built build(long seed, PreviewSampleStore store) {
        DiffusionBridgeConfig config = DiffusionBridgeConfig.fromSystemProperties();
        // Same tile chain the world generator uses (DiffusionTerrainGenerator
        // .productionTileSource): with the native water backend the preview
        // must show the fill-derived lakes, not the raw sea-level plane the
        // bridge serves when its hydrology is off.
        TerrainTileSource tiles = new DiffusionTileCache(config, seed);
        if (NativeWaterTiles.nativeBackendSelected()) {
            tiles = new NativeWaterTiles(tiles, BasinCache.production(config, seed),
                seed, config.tileSizeBlocks(), config.maxCachedTiles());
        }
        HeightMapGenerator heightMap = new HeightMapGenerator(tiles);
        BiomeManager biomes = new BiomeManager(tiles);

        // HEIGHT, TOPOGRAPHY and WATER deliberately share one HeightMapGenerator: they are
        // renderings of the same resolved tile, so a second generator would only double the
        // tile traffic to the bridge for identical data.
        Map<VisualizerKind, NoiseVisualizer> visualizers = new EnumMap<>(VisualizerKind.class);
        visualizers.put(VisualizerKind.HEIGHT, new HeightVisualizer(heightMap));
        visualizers.put(VisualizerKind.TOPOGRAPHY, new TopographyVisualizer(heightMap));
        visualizers.put(VisualizerKind.BIOME, new BiomeVisualizer(biomes));
        visualizers.put(VisualizerKind.WATER, new WaterVisualizer(heightMap));

        // Must agree with each visualizer's sample() for its channel — the cache stands in for it.
        TerrainColumns columns = (x, z, out) -> {
            out[PreviewChannel.HEIGHT.ordinal()] = heightMap.generateHeight(x, z);
            out[PreviewChannel.WATER.ordinal()] = heightMap.waterLevel(x, z);
            out[PreviewChannel.BIOME.ordinal()] = biomes.getBiome(x, z).ordinal();
        };
        DiffusionTileCache overviewTiles = new DiffusionTileCache(config, seed, TerrainMapperConfig.OVERVIEW_LOD);
        PreviewSource source = new PreviewSource(seed, columns, store,
                overviewColumns(overviewTiles, TerrainMapperConfig.OVERVIEW_LOD),
                TerrainMapperConfig.OVERVIEW_MIN_SPACING);

        AutoCloseable fullTiles = tiles instanceof AutoCloseable closeable ? closeable : null;
        AutoCloseable resources = () -> {
            try {
                overviewTiles.close();
            } finally {
                if (fullTiles != null) fullTiles.close();
            }
        };
        return new Built(visualizers, source, resources);
    }

    /**
     * Columns for far zoom, read from overview tiles of {@code lod} blocks per sample: one bulk
     * request per 2048-block square instead of 64 full tiles and their native water pass. The
     * tiles are addressed in sample units, so a world column maps to the sample it falls in.
     * Water is the sea only — inland lakes and rivers need the full tiles this skips.
     */
    private static TerrainColumns overviewColumns(TerrainTileSource overviewTiles, int lod) {
        HeightMapGenerator heights = new HeightMapGenerator(overviewTiles);
        BiomeManager biomes = new BiomeManager(overviewTiles);
        return (x, z, out) -> {
            int sx = Math.floorDiv(x, lod);
            int sz = Math.floorDiv(z, lod);
            out[PreviewChannel.HEIGHT.ordinal()] = heights.generateHeight(sx, sz);
            out[PreviewChannel.WATER.ordinal()] = heights.waterLevel(sx, sz);
            out[PreviewChannel.BIOME.ordinal()] = biomes.getBiome(sx, sz).ordinal();
        };
    }

    /** Called when the player picks Diffusion; nothing starts until a preview asks. */
    void activate() {
        active = true;
    }

    /**
     * Boots (or re-pins) the local services for {@code seed}, blocking for up to a couple of
     * minutes on a cold machine. Worker thread only — see {@code TerrainPreviewLoader}.
     */
    @Override
    public void startServices(long seed) {
        synchronized (serviceLock) {
            if (active) {
                TerrainServiceProcessManager.getInstance().ensureRunningForSeed(seed);
            }
        }
    }

    /** Stops the services off the render thread: stopping waits on a boot in progress. */
    @Override
    public void stopServices() {
        if (!active) return;
        active = false;
        Thread stopper = new Thread(() -> {
            synchronized (serviceLock) {
                if (!active) {
                    TerrainServiceProcessManager.getInstance().shutdown();
                }
            }
        }, "terrain-mapper-diffusion-stop");
        stopper.setDaemon(true);
        stopper.start();
    }
}
