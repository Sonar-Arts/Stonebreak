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
    private final NativeCubeGenerator generator;
    private final DiffusionProfiles profiles;

    private NativeTerrain(long seed, TerrainGeneratorType type, TerrainScalePreset scale,
                          NativeCubeGenerator generator, DiffusionProfiles profiles) {
        this.seed = seed;
        this.type = type;
        this.scale = scale;
        this.generator = generator;
        this.profiles = profiles;
    }

    /**
     * Builds the generator for a world. {@code states} supplies the stalagmite state ids the generator
     * writes into the STATE layer (fixed ids, see {@link BlockStateRegistry}).
     *
     * @throws IllegalStateException when {@code libstonebreak_worldgen} is missing or has another ABI
     */
    public static NativeTerrain create(long seed, TerrainGeneratorType type, TerrainScalePreset scale,
                                       BlockStateRegistry states) {
        TerrainGeneratorType t = type == null ? TerrainGeneratorType.STANDARD : type;
        TerrainScalePreset s = scale == null ? TerrainScalePreset.ONE_TO_FOUR : scale;
        NativeCubeGenerator.Config config = config(seed, s, states);
        DiffusionProfiles profiles = null;
        if (t == TerrainGeneratorType.DIFFUSION) {
            profiles = new DiffusionProfiles(seed, s);
            config.kind = NativeCubeGenerator.Kind.PROFILE;
            config.profiles = profiles;
            // Terrain-aware ravines and sinkholes from the model's carve node (TGMPipe v2 plane 6).
            config.flags |= NativeCubeGenerator.FLAG_PROFILE_CARVE;
        }
        try {
            return new NativeTerrain(seed, t, s, NativeCubeGenerator.create(config), profiles);
        } catch (RuntimeException e) {
            if (profiles != null) profiles.close();
            throw e;
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

    @Override
    public long getSeed() {
        return seed;
    }

    @Override
    public String lodCacheTag() {
        String base = "native-" + type.name().toLowerCase() + "-" + scale.name().toLowerCase();
        if (type == TerrainGeneratorType.DIFFUSION) {
            String ns = TGMPipe.getInstance().cacheNamespace(scale);
            return base + ":" + TGMPipe.MODEL_NAME + ":" + (ns != null ? ns : "offline");
        }
        return base;
    }

    @Override
    public void shutdown() {
        close();
    }

    /** Destroys the native generator (no stream may still use it) and the tile source. */
    @Override
    public void close() {
        generator.close();
        if (profiles != null) {
            profiles.close();
        }
    }

    // ---- column queries -----------------------------------------------------------------------------------

    public NativeCubeGenerator.ColumnInfo queryColumn(long x, long z) {
        return generator.queryColumn(x, z);
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
        generator.sampleCellOpenings(worldX0, worldZ0, cellsPerAxis, cellSize, cellHeights, outFloor, outCoverage);
    }

    @Override
    public void sampleColumns(int worldX0, int worldZ0, int count, int stride, int[] outHeights,
                              int[] outWaterLevels, BlockType[] outSurface,
                              VegetationGenerator.TreeSample[] outTrees) {
        sample(worldX0, worldZ0, count, stride, true, outHeights, outWaterLevels, outSurface, outTrees);
    }

    @Override
    public void sampleRawColumns(int worldX0, int worldZ0, int count, int stride, int[] outHeights,
                                 int[] outWaterLevels, BlockType[] outSurface) {
        sample(worldX0, worldZ0, count, stride, false, outHeights, outWaterLevels, outSurface, null);
    }

    private void sample(int x0, int z0, int count, int stride, boolean carved, int[] outHeights, int[] outWater,
                        BlockType[] outSurface, VegetationGenerator.TreeSample[] outTrees) {
        int n = count * count;
        int[] water = outWater != null ? new int[n] : null;
        short[] surface = outSurface != null ? new short[n] : null;
        int[] trees = outTrees != null ? new int[n] : null;
        generator.sampleColumns(x0, z0, count, stride, carved, outHeights, water, surface, trees);
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
        int[] spots = generator.probeCellTrees((long) chunkX * WorldConfiguration.CHUNK_SIZE,
            (long) chunkZ * WorldConfiguration.CHUNK_SIZE, cellsPerAxis, cellSize, cellHeights, water, surface);
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

        DiffusionProfiles(long seed, TerrainScalePreset scale) {
            TGMPipe.getInstance().ensureRunning(fraction -> { });
            this.tiles = new DiffusionTileCache(seed, scale);
            this.heights = new com.stonebreak.world.generation.diffusion.heightmap.HeightMapGenerator(tiles);
            this.biomes = new com.stonebreak.world.generation.diffusion.biomes.BiomeManager(tiles);
        }

        @Override
        public boolean fill(long cx, long cz, int[] height, int[] water, int[] riverFloor, int[] riverRoof,
                            byte[] biome, byte[] riverFlow, short[] carve) {
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
                }
            }
            return true;
        }

        @Override
        public void close() {
            tiles.close();
        }
    }
}
