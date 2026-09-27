package com.stonebreak.ui.terrainMapper.visualization;

import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.ui.terrainMapper.visualization.impl.BiomeVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.HeightVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.TopographyVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.RiverVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.impl.WaterVisualizer;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig;
import com.stonebreak.world.generation.diffusion.DiffusionTileCache;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.generation.diffusion.process.TerrainServiceProcessManager;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.water.BasinCache;
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
    /**
     * Outlives {@link #rebuild}: values are keyed by seed, so switching back to a seed already
     * explored shows its terrain again without resampling. Emptied only by {@link #clearPreviewData()}.
     */
    private final PreviewSampleStore previewStore =
            new PreviewSampleStore(TerrainMapperConfig.PREVIEW_CACHE_BUDGET_BYTES);
    private PreviewSource previewSource;
    private long seed;
    /** The tile chain the current visualizers read through, so {@link #rebuild}
     *  can release the previous one instead of leaking its threads. */
    private TerrainTileSource tileSource;
    /** The far-zoom overview chain (null on backends that cannot serve it). */
    private DiffusionTileCache overviewSource;

    public VisualizerRegistry(long seed) {
        rebuild(seed);
    }

    public long seed() { return seed; }

    public NoiseVisualizer get(VisualizerKind kind) {
        return visualizers.get(kind);
    }

    /** Cached terrain values for the current seed. Replaced, together with the visualizers, by {@link #rebuild}. */
    public PreviewSource previewSource() {
        return previewSource;
    }

    /** Forgets every sampled value, for every seed. Called when the mapper is closed. */
    public void clearPreviewData() {
        previewStore.clear();
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
        // The outgoing chain owns a BasinCache with two background threads of
        // its own; dropping the reference alone leaked them every seed change.
        closeTileSource();
        this.seed = newSeed;
        DiffusionBridgeConfig config = DiffusionBridgeConfig.fromSystemProperties();
        // Same tile chain the world generator uses (TerrainGenerationSystem
        // .productionTileSource): with the native water backend the preview
        // must show the fill-derived lakes, not the raw sea-level plane the
        // bridge serves when its hydrology is off.
        TerrainTileSource tileCache = new DiffusionTileCache(config, newSeed);
        if (NativeWaterTiles.nativeBackendSelected()) {
            tileCache = new NativeWaterTiles(tileCache, BasinCache.production(config, newSeed),
                newSeed, config.tileSizeBlocks(), config.maxCachedTiles());
        }
        this.tileSource = tileCache;
        HeightMapGenerator heightMap = new HeightMapGenerator(tileCache);
        BiomeManager biomes = new BiomeManager(tileCache);

        // HEIGHT, TOPOGRAPHY, WATER and RIVERS deliberately share one HeightMapGenerator: they are
        // renderings of the same resolved tile, so a second generator would only double the
        // tile traffic to the bridge for identical data.
        visualizers.put(VisualizerKind.HEIGHT, new HeightVisualizer(heightMap));
        visualizers.put(VisualizerKind.TOPOGRAPHY, new TopographyVisualizer(heightMap));
        visualizers.put(VisualizerKind.BIOME, new BiomeVisualizer(biomes));
        visualizers.put(VisualizerKind.WATER, new WaterVisualizer(heightMap));
        visualizers.put(VisualizerKind.RIVERS, new RiverVisualizer(heightMap));

        // Must agree with each visualizer's sample() for its channel — the cache stands in for it.
        TerrainColumns columns = (x, z, out) -> {
            out[PreviewChannel.HEIGHT.ordinal()] = heightMap.generateHeight(x, z);
            out[PreviewChannel.WATER.ordinal()] = heightMap.waterLevel(x, z);
            out[PreviewChannel.BIOME.ordinal()] = biomes.getBiome(x, z).ordinal();
            out[PreviewChannel.RIVER.ordinal()] = RiverVisualizer.code(heightMap.waterLevel(x, z),
                    heightMap.riverFloor(x, z), heightMap.riverRoof(x, z), heightMap.riverFlow(x, z));
        };
        this.previewSource = new PreviewSource(newSeed, columns, previewStore,
                overviewColumns(config, newSeed), TerrainMapperConfig.OVERVIEW_MIN_SPACING);
    }

    /**
     * Columns for far zoom: coarse tiles of {@link TerrainMapperConfig#OVERVIEW_LOD} blocks per
     * sample, straight from the model's 240 m cells (no refiner or river pipeline), so a zoomed-out
     * view costs a few coarse tiles instead of thousands of full ones. Only DaedalusTGM-Exp serves
     * them; on other backends the preview keeps sampling full tiles at every zoom.
     */
    private TerrainColumns overviewColumns(DiffusionBridgeConfig config, long seed) {
        if (!TerrainServiceProcessManager.modelSuppliesWater()) {
            return null;
        }
        int lod = TerrainMapperConfig.OVERVIEW_LOD;
        overviewSource = new DiffusionTileCache(config, seed, lod);
        HeightMapGenerator heights = new HeightMapGenerator(overviewSource);
        BiomeManager biomes = new BiomeManager(overviewSource);
        return (x, z, out) -> {
            int sx = Math.floorDiv(x, lod);
            int sz = Math.floorDiv(z, lod);
            out[PreviewChannel.HEIGHT.ordinal()] = heights.generateHeight(sx, sz);
            out[PreviewChannel.WATER.ordinal()] = heights.waterLevel(sx, sz);
            out[PreviewChannel.BIOME.ordinal()] = biomes.getBiome(sx, sz).ordinal();
            out[PreviewChannel.RIVER.ordinal()] = RiverVisualizer.code(heights.waterLevel(sx, sz),
                    heights.riverFloor(sx, sz), heights.riverRoof(sx, sz), heights.riverFlow(sx, sz));
        };
    }

    /** Releases the current tile chain. Safe to call more than once. */
    private void closeTileSource() {
        if (tileSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                System.err.println("[VisualizerRegistry] tile source close failed: " + e);
            }
        }
        tileSource = null;
        if (overviewSource != null) {
            overviewSource.close();
            overviewSource = null;
        }
    }
}
