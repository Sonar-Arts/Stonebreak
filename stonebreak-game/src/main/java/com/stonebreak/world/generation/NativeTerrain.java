package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.fastlod.FastLodChunkData;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.diffusion.DiffusionTileCache;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.tgmpipe.TGMPipe;
import com.stonebreak.world.generation.features.VegetationGenerator;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.voxel.BlockStateRegistry;
import com.stonebreak.world.voxel.StonebreakBlockTable;
import com.stonebreak.world.voxel.VoxelLayers;

import java.lang.foreign.MemorySegment;

/**
 * A world's terrain: the native cube generator ({@link NativeCubeGenerator}, SBW ABI 3) configured for this
 * world, plus the {@link TerrainGenerator} queries FastLOD, the terrain mapper, weather relief and spawn
 * finding ask - all answered natively ({@code sbw_query_column}, {@code sbw_sample_columns},
 * {@code sbw_sample_cell_openings}, {@code sbw_probe_cell_trees}). The Java generator is gone (#447).
 *
 * <p>{@link TerrainGeneratorType#STANDARD} is the native distilled-noise terrain;
 * {@link TerrainGeneratorType#DIFFUSION} is the native PROFILE kind whose column heights, water, rivers and
 * biomes come from DaedalusTGM-Exp tiles through the Java profile upcall ({@link DiffusionProfiles}),
 * everything else (carvers, caves, features) native.
 *
 * <p>Water levels come back from native as {@link NativeCubeGenerator#NO_WATER} when dry, which is the same
 * value as {@link WorldConfiguration#NO_WATER} (the translation below is kept so the two may not drift).
 */
public final class NativeTerrain implements TerrainGenerator, AutoCloseable {

    private final long seed;
    private final TerrainGeneratorType type;
    private final TerrainScalePreset scale;
    private final ClimateLayout climateLayout;
    private final HeightCurveVersion heightCurve;
    /** The Standard shape of a STANDARD world (DIFFUSION worlds carry it and ignore it). */
    private StandardShapeVersion standardShape = StandardShapeVersion.CURRENT;
    private final NativeCubeGenerator generator;
    private final DiffusionProfiles profiles;
    /** The config {@link #generator} was built from: the coarse generators copy it (DIFFUSION only). */
    private final NativeCubeGenerator.Config config;

    /**
     * Coarse-LOD generators of a DIFFUSION world (FX2, 2026-11-19; review R4 F5): FastLOD's coarse levels and the
     * weather relief sample the terrain every 8-16 blocks, yet each sample's column profile used to come from a
     * full-detail (lod 1) TGMPipe tile - in a real session 431 of 477 tiles the service built were 46-170 km away, for
     * those rings. These generators are the same native generator (same config, flags and rules) whose profiles come
     * from coarse tiles (a lod-L sample covers L x L blocks): {@link #coarseLods} = {2 for 8-block cells, the overview
     * lod for 16-block cells}. Created on first use; their caches never mix with the real cubes' ({@link #generator}
     * stays all lod 1). Index i serves lod {@code coarseLods[i]}; guarded by coarseLock.
     */
    private final int[] coarseLods;
    /** Column profiles each coarse generator keeps (the far rings are swept nearest-first: a few rings of columns). */
    private static final int COARSE_CACHE_COLUMNS = 2048;
    /** Coarse tiles kept per coarse lod in Java (a lod-4 tile covers 1024 x 1024 blocks). */
    private static final int COARSE_CACHED_TILES = 32;
    /** {@code -Dstonebreak.tgmpipe.coarseLod=off}: every sample from lod 1 tiles (the pre-FX2 behaviour, for A/B). */
    private static final boolean COARSE_ENABLED =
            !"off".equalsIgnoreCase(System.getProperty("stonebreak.tgmpipe.coarseLod"));
    /** The relief waits for this long without a more urgent tile request ({@link TGMPipe#awaitQuiet}) ... */
    private static final long RELIEF_QUIET_MS = Long.getLong("stonebreak.tgmpipe.reliefQuietMs", 1500L);
    /** ... but never longer than this per relief tile (clouds over a long flight still learn the terrain). */
    private static final long RELIEF_MAX_WAIT_MS = Long.getLong("stonebreak.tgmpipe.reliefMaxWaitMs", 20_000L);
    private final Object coarseLock = new Object();
    private final NativeCubeGenerator[] coarseGenerators = new NativeCubeGenerator[2];
    private final CoarseProfiles[] coarseProfiles = new CoarseProfiles[2];
    private final boolean[] coarseFailed = new boolean[2];

    private NativeTerrain(long seed, TerrainGeneratorType type, TerrainScalePreset scale, ClimateLayout climateLayout,
                          HeightCurveVersion heightCurve, NativeCubeGenerator generator, DiffusionProfiles profiles,
                          NativeCubeGenerator.Config config) {
        this.seed = seed;
        this.type = type;
        this.scale = scale;
        this.climateLayout = climateLayout;
        this.heightCurve = heightCurve;
        this.generator = generator;
        this.profiles = profiles;
        this.config = config;
        this.coarseLods = new int[] {2, overviewLod(scale)};
    }

    /**
     * The lod of the 16-block FastLOD cells' tiles: the first whose samples are whole 240 m planner cells (the service's
     * overview path, no river / detail pipeline), never under 4 - lod 4 at 1:4, 8 at 1:2, 16 at 1:1. Measured in the
     * service per lod-1 tile area (FX2): 1:4 lod 4 ~15 ms vs ~145-250 at lod 1 (R4); 1:2 lod 8 13 ms, lod 4 131 ms.
     */
    static int overviewLod(TerrainScalePreset scale) {
        double metres = scale.horizontalMetresPerBlock();
        int lod = 4;
        while (lod < 16 && lod * metres < 240.0 - 1e-6) {
            lod *= 2;
        }
        return lod;
    }

    /**
     * Builds the generator for a world. {@code states} supplies the stalagmite state ids the generator
     * writes into the STATE layer (fixed ids, see {@link BlockStateRegistry}).
     *
     * @throws IllegalStateException when {@code libstonebreak_worldgen} is missing or has another ABI
     */
    public static NativeTerrain create(long seed, TerrainGeneratorType type, TerrainScalePreset scale,
                                       BlockStateRegistry states) {
        return create(seed, type, scale, ClimateLayout.DEFAULT, states);
    }

    /**
     * {@link #create(long, TerrainGeneratorType, TerrainScalePreset, BlockStateRegistry)} for a world with the
     * climate {@code layout} (null = {@link ClimateLayout#DEFAULT}). Only DIFFUSION worlds use it: their tiles come
     * from the layout's own TGMPipe world. Standard worlds carry it and ignore it.
     */
    public static NativeTerrain create(long seed, TerrainGeneratorType type, TerrainScalePreset scale,
                                       ClimateLayout layout, BlockStateRegistry states) {
        return create(seed, type, scale, layout, null, states);
    }

    /**
     * {@link #create(long, TerrainGeneratorType, TerrainScalePreset, ClimateLayout, BlockStateRegistry)} for a world
     * created with the height curve {@code curve} (null = {@link HeightCurveVersion#CURRENT}; a saved world passes its
     * own). Only DIFFUSION worlds use it (their tiles come from that curve's TGMPipe world); Standard worlds ignore it.
     */
    public static NativeTerrain create(long seed, TerrainGeneratorType type, TerrainScalePreset scale,
                                       ClimateLayout layout, HeightCurveVersion curve, BlockStateRegistry states) {
        return create(seed, type, scale, layout, curve, null, states);
    }

    /**
     * {@link #create(long, TerrainGeneratorType, TerrainScalePreset, ClimateLayout, HeightCurveVersion,
     * BlockStateRegistry)} for a world created with the feature set {@code features} (null =
     * {@link WorldFeatureVersion#CURRENT}; a saved world passes its own). Only DIFFUSION worlds use it.
     */
    public static NativeTerrain create(long seed, TerrainGeneratorType type, TerrainScalePreset scale,
                                       ClimateLayout layout, HeightCurveVersion curve, WorldFeatureVersion features,
                                       BlockStateRegistry states) {
        return create(seed, type, scale, layout, curve, features, null, states);
    }

    /**
     * {@link #create(long, TerrainGeneratorType, TerrainScalePreset, ClimateLayout, HeightCurveVersion,
     * WorldFeatureVersion, BlockStateRegistry)} for a world created with the Standard shape {@code standard} (null =
     * {@link StandardShapeVersion#CURRENT}; a saved world passes its own). Only STANDARD worlds use it.
     */
    public static NativeTerrain create(long seed, TerrainGeneratorType type, TerrainScalePreset scale,
                                       ClimateLayout layout, HeightCurveVersion curve, WorldFeatureVersion features,
                                       StandardShapeVersion standard, BlockStateRegistry states) {
        WorldFeatureVersion f = WorldFeatureVersion.orCurrent(features);
        StandardShapeVersion sv = StandardShapeVersion.orCurrent(standard);
        TerrainGeneratorType t = type == null ? TerrainGeneratorType.STANDARD : type;
        TerrainScalePreset s = scale == null ? TerrainScalePreset.ONE_TO_FOUR : scale;
        ClimateLayout l = ClimateLayout.orDefault(layout);
        HeightCurveVersion c = HeightCurveVersion.orCurrent(curve);
        NativeCubeGenerator.Config config = config(seed, s, states);
        DiffusionProfiles profiles = null;
        if (t == TerrainGeneratorType.DIFFUSION) {
            profiles = new DiffusionProfiles(seed, s, l, c);
            config.kind = NativeCubeGenerator.Kind.PROFILE;
            config.profiles = profiles;
            // Terrain-aware ravines and sinkholes from the model's carve node (TGMPipe v2 plane 6).
            config.flags |= NativeCubeGenerator.FLAG_PROFILE_CARVE;
            // Height-curve v2 worlds: a sea floor of sand, gravel, clay and rock by depth / slope / shelter.
            if (c != HeightCurveVersion.V1) {
                config.flags |= NativeCubeGenerator.FLAG_SEA_FLOOR;
            }
            // Boulders, scree and erratics: worlds created with them only (never beside a saved world's old terrain).
            // Dev A/B only (never saved): -Dstonebreak.boulders=off renders a boulder world without them.
            if (f.boulders() && !"off".equalsIgnoreCase(System.getProperty("stonebreak.boulders"))) {
                config.flags |= NativeCubeGenerator.FLAG_BOULDERS;
                config.rockBank = f.rockBank();
            }
            // Land surface materials from the surface-detail node's channels (no-op for tiles without them): worlds
            // created with them only. Dev A/B only (never saved): -Dstonebreak.landSurface=off.
            if (f.landSurface() && !"off".equalsIgnoreCase(System.getProperty("stonebreak.landSurface"))) {
                config.flags |= NativeCubeGenerator.FLAG_LAND_SURFACE;
            }
            // Vegetation from the vegetation node's channels (no-op for tiles without them): worlds created with it
            // only. Dev A/B only (never saved): -Dstonebreak.vegetation=off.
            if (f.vegetation() && !"off".equalsIgnoreCase(System.getProperty("stonebreak.vegetation"))) {
                config.flags |= NativeCubeGenerator.FLAG_VEGETATION;
                if (f.openLand()) {
                    config.flags |= NativeCubeGenerator.FLAG_VEG_OPEN_LAND;
                }
            }
        } else {
            applyStandardShape(config, s, sv);
        }
        try {
            NativeTerrain terrain = new NativeTerrain(seed, t, s, l, c, NativeCubeGenerator.create(config), profiles,
                config);
            terrain.standardShape = sv;
            return terrain;
        } catch (RuntimeException e) {
            if (profiles != null) profiles.close();
            throw e;
        }
    }

    /**
     * The Standard shape {@code shape} on a Standard {@code config}: shape 2 (2026-11-19, distilled from
     * DaedalusTGM-Exp-2) = the scale's v2 height curve and the channel-driven surface features (the native generator
     * distils the channels itself), with the same dev A/B switches as DaedalusTGM worlds (never saved):
     * {@code -Dstonebreak.boulders=off}, {@code .landSurface=off}, {@code .vegetation=off}. Shared with tests.
     */
    public static void applyStandardShape(NativeCubeGenerator.Config config, TerrainScalePreset scale,
                                          StandardShapeVersion shape) {
        StandardShapeVersion sv = StandardShapeVersion.orCurrent(shape);
        config.standardShape = sv.number();
        if (!sv.surfaceFeatures()) {
            return;
        }
        NativeCubeGenerator.HeightCurveSpec curve = scale.heightCurve(sv.heightCurve());
        config.curveRates = curve.rates();
        config.curveKnots = curve.knots();
        config.flags |= NativeCubeGenerator.FLAG_SEA_FLOOR;
        if (!"off".equalsIgnoreCase(System.getProperty("stonebreak.boulders"))) {
            config.flags |= NativeCubeGenerator.FLAG_BOULDERS;
            config.rockBank = sv.rockBank();
        }
        if (!"off".equalsIgnoreCase(System.getProperty("stonebreak.landSurface"))) {
            config.flags |= NativeCubeGenerator.FLAG_LAND_SURFACE;
        }
        if (!"off".equalsIgnoreCase(System.getProperty("stonebreak.vegetation"))) {
            config.flags |= NativeCubeGenerator.FLAG_VEGETATION;
        }
    }

    /** The Standard config of a world (layers, ids, scale) - shared with tests. */
    public static NativeCubeGenerator.Config config(long seed, TerrainScalePreset scale, BlockStateRegistry states) {
        NativeCubeGenerator.Config config = new NativeCubeGenerator.Config();
        config.seed = seed;
        config.minY = WorldConfiguration.MIN_Y;
        config.maxY = WorldConfiguration.MAX_Y;
        config.seaLevel = WorldConfiguration.SEA_LEVEL;
        config.scale = scale;
        config.blockIds = NativeCubeGenerator.registryBlockIds();
        config.blockFlags = StonebreakBlockTable.flags();
        config.waterLayer = VoxelLayers.WATER;
        config.stateLayer = VoxelLayers.STATE;
        config.snowLayer = VoxelLayers.SNOW;
        config.waterSourceValue = 0; // source = 0 in the WATER layer (cv_fluid rule): ocean cubes stay empty
        config.waterRiverValues = VoxelLayers.riverRawValues();
        BlockStateRegistry s = states != null ? states : new BlockStateRegistry();
        config.stalagmiteStates = s.stalagmiteAnchorIds();
        config.stalagmitePartStates = s.stalagmitePartIds();
        return config;
    }

    /** True when the native generator library is loadable (else {@link NativeCubeGenerator#unavailableReason}). */
    public static boolean isAvailable() {
        return NativeCubeGenerator.isAvailable();
    }

    public NativeCubeGenerator nativeGenerator() {
        return generator;
    }

    /** The {@code const cv_generator*} for the world's stream. */
    public MemorySegment cvGenerator() {
        return generator.generator();
    }

    public TerrainGeneratorType type() {
        return type;
    }

    public TerrainScalePreset scale() {
        return scale;
    }

    /** The world's climate layout (used by DIFFUSION worlds only). */
    public ClimateLayout climateLayout() {
        return climateLayout;
    }

    /** The world's Standard shape (used by STANDARD worlds only). */
    public StandardShapeVersion standardShape() {
        return standardShape;
    }

    /** The world's height-curve version (used by DIFFUSION worlds only). */
    public HeightCurveVersion heightCurve() {
        return heightCurve;
    }

    @Override
    public long getSeed() {
        return seed;
    }

    @Override
    public String lodCacheTag() {
        String base = "native-" + type.name().toLowerCase() + "-" + scale.name().toLowerCase();
        if (type == TerrainGeneratorType.DIFFUSION) {
            // Belts keeps the pre-layout tag; any other layout is named, so even an offline tag never matches
            // another layout's cache (the namespace already differs per layout when the service is up).
            if (climateLayout != ClimateLayout.DEFAULT) {
                base += "-" + climateLayout.value();
            }
            if (heightCurve != HeightCurveVersion.V1) {   // likewise: v1 keeps its tag, later curves are named
                base += "-c" + heightCurve.number();
            }
            String ns = TGMPipe.getInstance().cacheNamespace(scale, climateLayout, heightCurve);
            return base + ":" + TGMPipe.MODEL_NAME + ":" + (ns != null ? ns : "offline");
        }
        // Standard shape 1 keeps its tag (its caches stay valid); later shapes are named
        return standardShape == StandardShapeVersion.V1 ? base : base + "-s" + standardShape.number();
    }

    @Override
    public void shutdown() {
        close();
    }

    /**
     * Destroys the native generator (no stream may still use it) and the tile source. Queries still running on other
     * threads (FastLOD workers, the weather relief, debug/spawn queries) finish first and later ones are refused with
     * {@link TerrainClosedException}: the generator stops admitting calls and fails its profile upcalls, the tile
     * source is closed (cancelling the tile waits those calls may be blocked in), then the generator waits for the
     * calls in flight and frees itself ({@link NativeCubeGenerator#close()}).
     */
    @Override
    public void close() {
        generator.beginClose();
        NativeCubeGenerator[] coarse;
        CoarseProfiles[] coarseSources;
        synchronized (coarseLock) {   // no coarse generator is created once the main one is closing (coarse())
            coarse = coarseGenerators.clone();
            coarseSources = coarseProfiles.clone();
        }
        for (NativeCubeGenerator g : coarse) {
            if (g != null) g.beginClose();
        }
        try {
            if (profiles != null) {
                profiles.close();
            }
            for (CoarseProfiles p : coarseSources) {
                if (p != null) p.close();
            }
        } finally {
            try {
                for (NativeCubeGenerator g : coarse) {
                    if (g != null) g.close();
                }
            } finally {
                generator.close();
            }
        }
    }

    /**
     * The generator to sample at {@code spacing} blocks: for DIFFUSION worlds a coarse one at 8+ blocks (lod 2 for
     * 8-block cells: 4 x 4 samples per cell; {@link #overviewLod} for 16+: 1-4 samples per cell), else the world's own.
     */
    private NativeCubeGenerator samplerFor(int spacing) {
        if (profiles == null || !COARSE_ENABLED || spacing < 8) {
            return generator;
        }
        return coarse(spacing >= 16 ? 1 : 0);
    }

    /** Coarse generator {@code i} ({@link #coarseLods}), built on first use; the world's generator if it cannot be. */
    private NativeCubeGenerator coarse(int i) {
        synchronized (coarseLock) {
            if (coarseGenerators[i] != null) {
                return coarseGenerators[i];
            }
            if (coarseFailed[i] || generator.isClosing()) {
                return generator;   // closing: it refuses the call like every other one
            }
            CoarseProfiles source = null;
            try {
                source = new CoarseProfiles(seed, coarseLods[i], scale, climateLayout, heightCurve);
                NativeCubeGenerator.Config c = copyConfig(config);
                c.profiles = source;
                c.cacheColumns = COARSE_CACHE_COLUMNS;
                coarseGenerators[i] = NativeCubeGenerator.create(c);
                coarseProfiles[i] = source;
                return coarseGenerators[i];
            } catch (RuntimeException e) {
                coarseFailed[i] = true;
                if (source != null) source.close();
                org.slf4j.LoggerFactory.getLogger(NativeTerrain.class).warn(
                    "coarse LOD generator (lod {}) unavailable, far terrain samples full-detail tiles: {}",
                    coarseLods[i], e.toString());
                return generator;
            }
        }
    }

    private static NativeCubeGenerator.Config copyConfig(NativeCubeGenerator.Config from) {
        NativeCubeGenerator.Config c = new NativeCubeGenerator.Config();
        c.kind = from.kind;
        c.seed = from.seed;
        c.minY = from.minY;
        c.maxY = from.maxY;
        c.seaLevel = from.seaLevel;
        c.flags = from.flags;
        c.scale = from.scale;
        c.blockIds = from.blockIds.clone();
        c.blockFlags = from.blockFlags.clone();
        c.waterLayer = from.waterLayer;
        c.stateLayer = from.stateLayer;
        c.snowLayer = from.snowLayer;
        c.waterSourceValue = from.waterSourceValue;
        c.waterRiverValues = from.waterRiverValues.clone();
        c.stalagmiteStates = from.stalagmiteStates.clone();
        c.stalagmitePartStates = from.stalagmitePartStates.clone();
        c.cacheColumns = from.cacheColumns;
        c.rockBank = from.rockBank;
        c.standardShape = from.standardShape;
        c.curveRates = from.curveRates != null ? from.curveRates.clone() : null;
        c.curveKnots = from.curveKnots != null ? from.curveKnots.clone() : null;
        c.profiles = from.profiles;
        return c;
    }

    /**
     * The weather's relief: from the coarsest tiles ({@link #overviewLod}: the clouds read 12-block cells), after every
     * LOD tile
     * ({@link TGMPipe#PRIORITY_RELIEF}). It reached 6,000+ blocks out at lod 1 before.
     */
    @Override
    public void sampleReliefHeights(int worldX0, int worldZ0, int count, int stride, int[] outHeights) {
        if (profiles == null) {
            TerrainGenerator.super.sampleReliefHeights(worldX0, worldZ0, count, stride, outHeights);
            return;
        }
        // Background work: only once the chunks and FastLOD have stopped asking for tiles for a moment (at most 20 s).
        TGMPipe.awaitQuiet(RELIEF_QUIET_MS, RELIEF_MAX_WAIT_MS);
        DiffusionTileCache.deferred(TGMPipe.PRIORITY_RELIEF, () -> {
            int[] water = new int[count * count];
            sample(samplerFor(Math.max(16, stride)), worldX0, worldZ0, count, stride, false, outHeights, water, null,
                    null);
            for (int i = 0; i < outHeights.length && i < water.length; i++) {
                outHeights[i] = Math.max(outHeights[i], water[i]);
            }
            return null;
        });
    }

    /** True once {@link #close()} began (every query is refused from then on). */
    public boolean isClosing() {
        return generator.isClosing();
    }

    /**
     * A failed native query as the exception to throw: {@link TerrainClosedException} when the terrain is shutting
     * down - the generator is closing, or this DIFFUSION world's tile service was stopped on purpose (quit) so the
     * profile upcall had nothing to answer with - else {@code e} unchanged.
     */
    private RuntimeException failure(RuntimeException e) {
        if (e instanceof TerrainClosedException) {
            return e;
        }
        if (generator.isClosing()) {
            return new TerrainClosedException("terrain closed: " + e.getMessage(), e);
        }
        if (profiles != null && TGMPipe.getInstance().isShutDown()) {
            return new TerrainClosedException("terrain service stopped: " + e.getMessage(), e);
        }
        return e;
    }

    // ---- column queries -----------------------------------------------------------------------------------

    public NativeCubeGenerator.ColumnInfo queryColumn(long x, long z) {
        try {
            return generator.queryColumn(x, z);
        } catch (RuntimeException e) {
            throw failure(e);
        }
    }

    private static int water(int nativeWater) {
        return nativeWater == NativeCubeGenerator.NO_WATER ? WorldConfiguration.NO_WATER : nativeWater;
    }

    @Override
    public BiomeType getBiomeAt(int x, int z) {
        int b = queryColumn(x, z).biome();
        BiomeType[] all = BiomeType.values();
        return b >= 0 && b < all.length ? all[b] : BiomeType.PLAINS;
    }

    @Override
    public int getBaseHeightAt(int x, int z) {
        return queryColumn(x, z).height();
    }

    @Override
    public int getShapedHeightAt(int x, int z) {
        return queryColumn(x, z).height();
    }

    @Override
    public int getFinalTerrainHeightAt(int x, int z) {
        return queryColumn(x, z).height();
    }

    @Override
    public int getWaterLevelAt(int x, int z) {
        return water(queryColumn(x, z).waterLevel());
    }

    @Override
    public int carvedSurfaceHeight(int worldX, int worldZ) {
        return queryColumn(worldX, worldZ).carvedHeight();
    }

    @Override
    public void sampleCellOpenings(int worldX0, int worldZ0, int cellsPerAxis, int cellSize,
                                   int[] cellHeights, int[] outFloor, byte[] outCoverage) {
        try {
            samplerFor(cellSize).sampleCellOpenings(worldX0, worldZ0, cellsPerAxis, cellSize, cellHeights, outFloor, outCoverage);
        } catch (RuntimeException e) {
            throw failure(e);
        }
    }

    @Override
    public void sampleCellOpenings(int worldX0, int worldZ0, int cellsPerAxis, int cellSize,
                                   int[] cellHeights, int[] outFloor, byte[] outCoverage, int[] outWater) {
        try {
            samplerFor(cellSize).sampleCellOpenings(worldX0, worldZ0, cellsPerAxis, cellSize, cellHeights, outFloor, outCoverage,
                outWater);
        } catch (RuntimeException e) {
            throw failure(e);
        }
        if (outWater != null) {
            for (int i = 0; i < outWater.length; i++) outWater[i] = water(outWater[i]);
        }
    }

    @Override
    public void sampleColumns(int worldX0, int worldZ0, int count, int stride, int[] outHeights,
                              int[] outWaterLevels, BlockType[] outSurface,
                              VegetationGenerator.TreeSample[] outTrees) {
        sample(samplerFor(stride), worldX0, worldZ0, count, stride, true, outHeights, outWaterLevels, outSurface,
                outTrees);
    }

    @Override
    public void sampleRawColumns(int worldX0, int worldZ0, int count, int stride, int[] outHeights,
                                 int[] outWaterLevels, BlockType[] outSurface) {
        sample(samplerFor(stride), worldX0, worldZ0, count, stride, false, outHeights, outWaterLevels, outSurface,
                null);
    }

    private void sample(NativeCubeGenerator generator, int x0, int z0, int count, int stride, boolean carved,
                        int[] outHeights, int[] outWater, BlockType[] outSurface,
                        VegetationGenerator.TreeSample[] outTrees) {
        int n = count * count;
        int[] water = outWater != null ? new int[n] : null;
        short[] surface = outSurface != null ? new short[n] : null;
        int[] trees = outTrees != null ? new int[n] : null;
        try {
            generator.sampleColumns(x0, z0, count, stride, carved, outHeights, water, surface, trees);
        } catch (RuntimeException e) {
            throw failure(e);
        }
        for (int i = 0; i < n; i++) {
            if (outWater != null) outWater[i] = water(water[i]);
            if (outSurface != null) {
                BlockType bt = BlockType.getById(Short.toUnsignedInt(surface[i]));
                outSurface[i] = bt != null ? bt : BlockType.STONE;
            }
            if (outTrees != null) outTrees[i] = treeSample(trees[i]);
        }
    }

    /** A native tree code ({@code (kind + 1) | trunk << 8}, 0 none) as a sample, or null. */
    static VegetationGenerator.TreeSample treeSample(int code) {
        if (code == 0) {
            return null;
        }
        int kind = (code & 0xFF) - 1;
        VegetationGenerator.TreeKind[] kinds = VegetationGenerator.TreeKind.values();
        if (kind < 0 || kind >= kinds.length) {
            return null;
        }
        return new VegetationGenerator.TreeSample(kinds[kind], (code >>> 8) & 0xFF);
    }

    @Override
    public int[] probeCellTrees(int chunkX, int chunkZ, int cellsPerAxis, int cellSize,
                                int[] cellHeights, int[] cellWaterLevels, BlockType[] cellSurface) {
        int n = cellsPerAxis * cellsPerAxis;
        int[] water = new int[n];
        short[] surface = new short[n];
        for (int i = 0; i < n; i++) {
            water[i] = cellWaterLevels[i] == WorldConfiguration.NO_WATER ? NativeCubeGenerator.NO_WATER : cellWaterLevels[i];
            surface[i] = (short) (cellSurface[i] != null ? cellSurface[i].getId() : 0);
        }
        int[] spots;
        try {
            spots = samplerFor(cellSize).probeCellTrees((long) chunkX * WorldConfiguration.CHUNK_SIZE,
                (long) chunkZ * WorldConfiguration.CHUNK_SIZE, cellsPerAxis, cellSize, cellHeights, water, surface);
        } catch (RuntimeException e) {
            throw failure(e);
        }
        VegetationGenerator.TreeKind[] kinds = VegetationGenerator.TreeKind.values();
        int[] out = new int[spots.length];
        for (int i = 0; i < spots.length; i++) {
            int s = spots[i];
            int lx = s & 0xFF, lz = (s >>> 8) & 0xFF, kind = (s >>> 16) & 3, trunk = (s >>> 18) & 0xFF;
            out[i] = FastLodChunkData.packTreeSpot(lx, lz, kinds[kind], trunk);
        }
        return out;
    }

    /**
     * The DaedalusTGM-Exp column profiles of a DIFFUSION world: the diffusion tile cache (TGMPipe) read
     * through the diffusion height and biome mappers, four 16x16 tile reads per 32x32 column cube. Runs on
     * native generator threads; may block on TGMPipe.
     */
    static final class DiffusionProfiles implements NativeCubeGenerator.ColumnProfiles, AutoCloseable {
        private static final int CS = WorldConfiguration.CHUNK_SIZE;
        private final DiffusionTileCache tiles;
        private final com.stonebreak.world.generation.diffusion.heightmap.HeightMapGenerator heights;
        private final com.stonebreak.world.generation.diffusion.biomes.BiomeManager biomes;

        DiffusionProfiles(long seed, TerrainScalePreset scale, ClimateLayout layout, HeightCurveVersion curve) {
            TGMPipe.getInstance().ensureRunning(fraction -> { });
            this.tiles = new DiffusionTileCache(seed, scale, layout, curve);
            this.heights = new com.stonebreak.world.generation.diffusion.heightmap.HeightMapGenerator(tiles);
            this.biomes = new com.stonebreak.world.generation.diffusion.biomes.BiomeManager(tiles,
                    com.stonebreak.world.generation.diffusion.biomes.BeachRule.of(scale, curve));
        }

        @Override
        public boolean fill(long cx, long cz, int[] height, int[] water, int[] riverFloor, int[] riverRoof,
                            byte[] biome, byte[] riverFlow, short[] carve) {
            byte[] surface = new byte[NativeCubeGenerator.SURFACE_SLOTS * 1024];
            java.util.Arrays.fill(surface, (byte) 0xFF);
            return fill(cx, cz, height, water, riverFloor, riverRoof, biome, riverFlow, carve, surface);
        }

        /** Also copies the tiles' surface channels (TGMPipe v3) into their native slots; absent ones stay 255. */
        @Override
        public boolean fill(long cx, long cz, int[] height, int[] water, int[] riverFloor, int[] riverRoof,
                            byte[] biome, byte[] riverFlow, short[] carve, byte[] surface) {
            int[] h = new int[CS * CS], w = new int[CS * CS], f = new int[CS * CS], r = new int[CS * CS],
                flow = new int[CS * CS];
            BiomeType[] b = new BiomeType[CS * CS];
            for (int sx = 0; sx < 2; sx++) {
                for (int sz = 0; sz < 2; sz++) {
                    int chunkX = Math.toIntExact(cx * 2 + sx);
                    int chunkZ = Math.toIntExact(cz * 2 + sz);
                    heights.populateChunkHeights(chunkX, chunkZ, h, w, f, r, flow);
                    biomes.populateChunkBiomes(chunkX, chunkZ, h, b);
                    TerrainTile tile = tiles.getTile(chunkX * CS, chunkZ * CS);   // a chunk never straddles tiles
                    for (int x = 0; x < CS; x++) {
                        for (int z = 0; z < CS; z++) {
                            int src = x * CS + z;
                            int dst = (sz * CS + z) * 32 + (sx * CS + x);
                            height[dst] = h[src];
                            water[dst] = w[src] == TerrainTile.NO_WATER ? NativeCubeGenerator.NO_WATER : w[src];
                            riverFloor[dst] = f[src];
                            riverRoof[dst] = r[src];
                            biome[dst] = (byte) (b[src] != null ? b[src].ordinal() : 0);
                            riverFlow[dst] = (byte) (flow[src] < 0 || flow[src] > 7 ? -1 : flow[src]);
                            carve[dst] = tile.carveAt(chunkX * CS + x, chunkZ * CS + z);
                        }
                    }
                    copySurface(tile, chunkX, chunkZ, sx, sz, surface);
                }
            }
            return true;
        }

        /** The tile's surface channel planes for one 16x16 chunk into the column cube's slot planes. */
        private static void copySurface(TerrainTile tile, int chunkX, int chunkZ, int sx, int sz, byte[] surface) {
            var channels = tile.surface();
            if (channels.isEmpty()) {
                return;
            }
            for (int slot = 0; slot < NativeCubeGenerator.SURFACE_SLOTS; slot++) {
                byte[] plane = channels.plane(slot);
                if (plane == null) {
                    continue;
                }
                for (int x = 0; x < CS; x++) {
                    int row = chunkX * CS + x - tile.worldI1();
                    for (int z = 0; z < CS; z++) {
                        int col = chunkZ * CS + z - tile.worldJ1();
                        surface[slot * 1024 + (sz * CS + z) * 32 + (sx * CS + x)] = plane[row * tile.width() + col];
                    }
                }
            }
        }

        @Override
        public void close() {
            tiles.close();
        }
    }

    /**
     * Column profiles from coarse (lod L) DaedalusTGM-Exp tiles, for the coarse-LOD generators only: each block column
     * takes the tile sample covering it ({@code floor(x / L)}, sample units), so a profile is the lod-L terrain with
     * L x L-block plateaus - what a FastLOD cell of 4L blocks averages anyway. Biomes, water, rivers, carve codes and
     * surface channels by the same rules as {@link DiffusionProfiles}; a column cube never straddles two coarse tiles
     * (32 divides 256 L).
     */
    static final class CoarseProfiles implements NativeCubeGenerator.ColumnProfiles, AutoCloseable {
        private static final int TOP = WorldConfiguration.MAX_Y;
        private final int lod;
        /** Tiles in SAMPLE units (block / lod). */
        private final com.stonebreak.world.generation.diffusion.TerrainTileSource tiles;
        private final AutoCloseable closer;
        /** The world's beach height band (BeachRule.of); the coarse reach is one sample. */
        private final com.stonebreak.world.generation.diffusion.biomes.BeachRule beach;

        CoarseProfiles(long seed, int lod, TerrainScalePreset scale, ClimateLayout layout, HeightCurveVersion curve) {
            this(lod, new DiffusionTileCache(seed, lod, TGMPipe.PRIORITY_LOD, scale, layout, curve,
                    COARSE_CACHED_TILES), com.stonebreak.world.generation.diffusion.biomes.BeachRule.of(scale, curve));
        }

        /** Test seam: lod-{@code lod} tiles from {@code tiles} (sample units), the legacy beach rule. */
        <S extends com.stonebreak.world.generation.diffusion.TerrainTileSource & AutoCloseable> CoarseProfiles(
                int lod, S tiles) {
            this(lod, tiles, com.stonebreak.world.generation.diffusion.biomes.BeachRule.LEGACY);
        }

        <S extends com.stonebreak.world.generation.diffusion.TerrainTileSource & AutoCloseable> CoarseProfiles(
                int lod, S tiles, com.stonebreak.world.generation.diffusion.biomes.BeachRule beach) {
            if (lod < 2 || 32 % lod != 0) {
                throw new IllegalArgumentException("coarse lod must be 2..32 and divide 32, got " + lod);
            }
            this.lod = lod;
            this.tiles = tiles;
            this.closer = tiles;
            this.beach = beach;
        }

        @Override
        public boolean fill(long cx, long cz, int[] height, int[] water, int[] riverFloor, int[] riverRoof,
                            byte[] biome, byte[] riverFlow, short[] carve) {
            byte[] surface = new byte[NativeCubeGenerator.SURFACE_SLOTS * 1024];
            java.util.Arrays.fill(surface, (byte) 0xFF);
            return fill(cx, cz, height, water, riverFloor, riverRoof, biome, riverFlow, carve, surface);
        }

        @Override
        public boolean fill(long cx, long cz, int[] height, int[] water, int[] riverFloor, int[] riverRoof,
                            byte[] biome, byte[] riverFlow, short[] carve, byte[] surface) {
            int n = 32 / lod;                                  // samples per axis in this column cube
            int si0 = Math.toIntExact(Math.floorDiv(cx * 32, lod));
            int sj0 = Math.toIntExact(Math.floorDiv(cz * 32, lod));
            TerrainTile tile = tiles.getTile(si0, sj0);
            var channels = tile.surface();
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    int si = si0 + a, sj = sj0 + b;
                    int h = Math.max(1, Math.min(tile.heightAt(si, sj), TOP - 1));
                    short w = tile.waterLevelAt(si, sj);
                    int wv = w == TerrainTile.NO_WATER ? NativeCubeGenerator.NO_WATER : w;
                    int fl = tile.riverFloorAt(si, sj), rf = tile.riverRoofAt(si, sj);
                    short f = tile.riverFlowAt(si, sj);
                    byte flow = (byte) (f < 0 || f > 7 ? -1 : f);
                    BiomeType bt = com.stonebreak.world.generation.diffusion.biomes.BiomeManager.biomeAt(tile, si, sj, h, beach);
                    byte bo = (byte) (bt != null ? bt.ordinal() : 0);
                    short cv = tile.carveAt(si, sj);
                    int src = (si - tile.worldI1()) * tile.width() + (sj - tile.worldJ1());
                    for (int x = a * lod; x < (a + 1) * lod; x++) {
                        for (int z = b * lod; z < (b + 1) * lod; z++) {
                            int dst = z * 32 + x;
                            height[dst] = h;
                            water[dst] = wv;
                            riverFloor[dst] = fl;
                            riverRoof[dst] = rf;
                            biome[dst] = bo;
                            riverFlow[dst] = flow;
                            carve[dst] = cv;
                        }
                    }
                    if (!channels.isEmpty()) {
                        for (int slot = 0; slot < NativeCubeGenerator.SURFACE_SLOTS; slot++) {
                            byte[] plane = channels.plane(slot);
                            if (plane == null) continue;
                            byte v = plane[src];
                            for (int x = a * lod; x < (a + 1) * lod; x++) {
                                for (int z = b * lod; z < (b + 1) * lod; z++) {
                                    surface[slot * 1024 + z * 32 + x] = v;
                                }
                            }
                        }
                    }
                }
            }
            return true;
        }

        @Override
        public void close() {
            try {
                closer.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
