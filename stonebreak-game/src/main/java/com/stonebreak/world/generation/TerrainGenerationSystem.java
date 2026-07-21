package com.stonebreak.world.generation;

import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.commonChunkOperations.CcoFactory;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.features.OreGenerator;
import com.stonebreak.world.generation.features.SurfaceDecorationGenerator;
import com.stonebreak.world.generation.features.VegetationGenerator;
import com.stonebreak.world.generation.heightmap.Density3D;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.generation.heightmap.PerlinWormCarver;
import com.stonebreak.world.generation.noise.NoiseRouter;

import java.util.BitSet;
import com.stonebreak.world.generation.terrain.MobGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Random;

/**
 * Orchestrates per-chunk terrain and feature generation by delegating to focused subsystems.
 */
public class TerrainGenerationSystem {
    public static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    public static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private final long seed;
    private final NoiseRouter noiseRouter;
    private final HeightMapGenerator heightMapGenerator;
    private final BiomeManager biomeManager;
    private final OreGenerator oreGenerator;
    private final VegetationGenerator vegetationGenerator;
    private final SurfaceDecorationGenerator decorationGenerator;
    private final DeterministicRandom deterministicRandom;
    private final Density3D density3D;
    private final PerlinWormCarver wormCarver;
    private final CavernCarver cavernCarver;
    private final MegaCavernCarver megaCavernCarver;

    private final Random animalRandom = new Random();
    private final Object animalRandomLock = new Object();

    /** Native worm-carver terrain context; 0 = Java carver path. */
    private final long nativeCarverCtx;

    public TerrainGenerationSystem(long seed) {
        this.seed = seed;
        this.deterministicRandom = new DeterministicRandom(seed);
        this.noiseRouter = new NoiseRouter(seed);
        this.heightMapGenerator = new HeightMapGenerator(noiseRouter);
        this.biomeManager = new BiomeManager(noiseRouter, heightMapGenerator);
        this.oreGenerator = new OreGenerator(deterministicRandom);
        this.vegetationGenerator = new VegetationGenerator(deterministicRandom);
        this.decorationGenerator = new SurfaceDecorationGenerator(deterministicRandom, heightMapGenerator, seed);
        this.density3D = new Density3D(seed);
        this.wormCarver = new PerlinWormCarver(seed, heightMapGenerator);
        this.cavernCarver = new CavernCarver(seed, heightMapGenerator);
        this.megaCavernCarver = new MegaCavernCarver(seed, heightMapGenerator);
        this.wormCarver.setCavernCarver(cavernCarver);
        this.wormCarver.setMegaCavernCarver(megaCavernCarver);

        // Native worm carving only when the native noise backend is active:
        // the kernel evaluates heights from the same FastNoise2 channels, so
        // its surface gates agree with the Java-side terrain. On the Java
        // backend those channels don't exist — the Java carver stays in charge.
        long carverCtx = 0L;
        if (com.stonebreak.world.generation.noise.TerrainNoise.backend()
                == com.stonebreak.world.generation.noise.TerrainNoise.Backend.NATIVE
            && !"java".equalsIgnoreCase(System.getProperty("stonebreak.carver.backend", "auto"))) {
            carverCtx = NoiseRouter.createCarverTerrainContext(seed,
                HeightMapGenerator.splineXs(), HeightMapGenerator.splineYs(),
                HeightMapGenerator.splineSizes(), HeightMapGenerator.DETAIL_AMPLITUDE);
            com.stonebreak.world.generation.noise.TerrainNoise.destroyTerrainOnCollect(this, carverCtx);
        }
        this.nativeCarverCtx = carverCtx;
    }

    /**
     * Worm carve mask via the native kernel, with cavern-connector anchors
     * precomputed by the Java cavern carvers so cavern placement stays
     * consistent with their rasterization. Falls back to the Java carver on
     * any kernel failure.
     */
    private java.util.BitSet nativeWormMask(int chunkX, int chunkZ, int[] heights) {
        int radius = PerlinWormCarver.scanRadius();
        java.util.ArrayList<int[]> anchorChunkList = new java.util.ArrayList<>();
        java.util.ArrayList<float[]> anchorList = new java.util.ArrayList<>();
        for (int dcx = -radius; dcx <= radius; dcx++) {
            for (int dcz = -radius; dcz <= radius; dcz++) {
                int srcCx = chunkX + dcx;
                int srcCz = chunkZ + dcz;
                if (!wormCarver.hasWormAt(srcCx, srcCz)) {
                    continue;
                }
                float[] anchor = wormCarver.cavernAnchorFor(srcCx, srcCz);
                if (anchor != null) {
                    anchorChunkList.add(new int[]{srcCx, srcCz});
                    anchorList.add(anchor);
                }
            }
        }
        int n = anchorChunkList.size();
        int[] anchorChunks = n == 0 ? null : new int[n * 2];
        float[] anchors = n == 0 ? null : new float[n * 3];
        for (int i = 0; i < n; i++) {
            anchorChunks[i * 2] = anchorChunkList.get(i)[0];
            anchorChunks[i * 2 + 1] = anchorChunkList.get(i)[1];
            float[] anchor = anchorList.get(i);
            anchors[i * 3] = anchor[0];
            anchors[i * 3 + 1] = anchor[1];
            anchors[i * 3 + 2] = anchor[2];
        }
        long[] mask = new long[1024];
        long carved = com.openmason.engine.cenda.CendaKernels.carveWorms(
            nativeCarverCtx, chunkX, chunkZ, heights, anchorChunks, anchors, mask);
        if (carved < 0) {
            return wormCarver.carveMaskForChunk(chunkX, chunkZ, heights);
        }
        return java.util.BitSet.valueOf(mask);
    }

    public long getSeed() {
        return seed;
    }

    public float getContinentalnessAt(int x, int z) {
        return noiseRouter.continentalness(x, z);
    }

    public float getErosionAt(int x, int z) {
        return noiseRouter.erosion(x, z);
    }

    public float getPeaksValleysAt(int x, int z) {
        return noiseRouter.peaksValleys(x, z);
    }

    public BiomeType getBiomeAt(int x, int z) {
        return biomeManager.getBiome(x, z);
    }

    public float getMoistureAt(int x, int z) {
        return biomeManager.getMoisture(x, z);
    }

    public float getTemperatureAt(int x, int z) {
        return biomeManager.getTemperature(x, z);
    }

    /** Continentalness-only base height (debug). */
    public int getBaseHeightAt(int x, int z) {
        return heightMapGenerator.baseHeight(x, z);
    }

    /** Shape with PV/erosion, no surface detail (debug). */
    public int getShapedHeightAt(int x, int z) {
        return heightMapGenerator.shapedHeight(x, z);
    }

    /** Final terrain height as used by chunk generation. */
    public int getFinalTerrainHeightAt(int x, int z) {
        return heightMapGenerator.generateHeight(x, z);
    }

    /**
     * Returns the surface block a column would place at its top air-adjacent cell,
     * derived from the same biome rules as {@link #determineBlockType}. Submerged
     * columns (surface below sea level) are reported as {@link BlockType#WATER} so
     * coarse renderers can paint a water sheet without block data.
     */
    public BlockType getSurfaceBlockAt(int worldX, int worldZ) {
        int height = heightMapGenerator.generateHeight(worldX, worldZ);
        if (height < SEA_LEVEL) {
            return BlockType.WATER;
        }
        return surfaceBlock(biomeManager.getBiome(worldX, worldZ));
    }

    /** Deterministic RNG for shared probing logic (tree placement, etc.). */
    public DeterministicRandom getDeterministicRandom() {
        return deterministicRandom;
    }

    /**
     * Probes whether a tree would be placed at this column, without mutating any
     * chunk. Distant-terrain LOD uses this to emit silhouette geometry matching
     * the real generator's deterministic placements.
     */
    public com.stonebreak.world.generation.features.VegetationGenerator.TreeSample getTreeAt(int worldX, int worldZ) {
        int height = heightMapGenerator.generateHeight(worldX, worldZ);
        if (height < SEA_LEVEL) return null;
        BlockType surface = surfaceBlock(biomeManager.getBiome(worldX, worldZ));
        BiomeType biome = biomeManager.getBiome(worldX, worldZ);
        return com.stonebreak.world.generation.features.VegetationGenerator.probeTree(
                worldX, worldZ, biome, surface, deterministicRandom);
    }

    /**
     * Batched column probe for coarse samplers (FastLOD): fills heights and,
     * optionally, surface blocks / tree samples for a {@code count x count}
     * grid at block positions {@code (worldX0 + ix*stride, worldZ0 + iz*stride)},
     * indexed {@code [ix*count + iz]}. Six channel fills replace thousands of
     * per-point samples; results are bit-identical to {@link #getFinalTerrainHeightAt},
     * {@link #getSurfaceBlockAt} and {@link #getTreeAt} at the same coordinates.
     *
     * @param outSurface nullable; length count*count when present
     * @param outTrees   nullable; length count*count when present (requires outSurface logic)
     */
    public void sampleColumns(int worldX0, int worldZ0, int count, int stride,
                              int[] outHeights,
                              BlockType[] outSurface,
                              com.stonebreak.world.generation.features.VegetationGenerator.TreeSample[] outTrees) {
        int cells = count * count;
        float[] c = new float[cells];
        float[] pv = new float[cells];
        float[] e = new float[cells];
        float[] d = new float[cells];
        noiseRouter.fillShapeChannels(worldX0, worldZ0, count, count, stride, c, pv, e, d);

        float[] tRaw = null;
        float[] mRaw = null;
        boolean needBiomes = outSurface != null || outTrees != null;
        if (needBiomes) {
            tRaw = new float[cells];
            mRaw = new float[cells];
            noiseRouter.fillClimateChannels(worldX0, worldZ0, count, count, stride, tRaw, mRaw);
        }

        for (int ix = 0; ix < count; ix++) {
            for (int iz = 0; iz < count; iz++) {
                int idx = ix * count + iz;
                int height = heightMapGenerator.heightFromChannels(c[idx], pv[idx], e[idx], d[idx]);
                outHeights[idx] = height;
                if (!needBiomes) {
                    continue;
                }
                BlockType surface;
                BiomeType biome = null;
                if (height < SEA_LEVEL) {
                    surface = BlockType.WATER;
                } else {
                    // Same tuple as BiomeManager.getBiome: temperature is chilled
                    // by the SHAPED height (no detail), matching the per-point path.
                    int shaped = heightMapGenerator.shapedFromChannels(c[idx], pv[idx], e[idx]);
                    biome = biomeManager.selectBiome(new com.stonebreak.world.generation.noise.MultiNoiseSample(
                        c[idx], e[idx], pv[idx],
                        com.stonebreak.world.generation.noise.NoiseRouter.temperatureFromRaw(tRaw[idx], shaped),
                        com.stonebreak.world.generation.noise.NoiseRouter.moistureFromRaw(mRaw[idx])));
                    surface = surfaceBlock(biome);
                }
                if (outSurface != null) {
                    outSurface[idx] = surface;
                }
                if (outTrees != null) {
                    outTrees[idx] = (height < SEA_LEVEL) ? null
                        : com.stonebreak.world.generation.features.VegetationGenerator.probeTree(
                            worldX0 + ix * stride, worldZ0 + iz * stride, biome, surface, deterministicRandom);
                }
            }
        }
    }

    /**
     * Result of terrain-only generation: the chunk plus the column profile
     * (heights + biomes) so deferred feature population can reuse it instead
     * of resampling the noise stack.
     */
    public record TerrainResult(Chunk chunk, ColumnProfile profile) {}

    /**
     * Generates terrain blocks for a chunk. Features are populated separately
     * once neighbor chunks exist (prevents recursive generation across chunk borders).
     */
    public TerrainResult generateTerrainOnly(int chunkX, int chunkZ) {
        updateLoadingProgress("Generating Base Terrain Shape");

        int[] heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BiomeType[] biomes = new BiomeType[CHUNK_SIZE * CHUNK_SIZE];

        // Shape first (noise-driven), then skin with biomes. Biomes do not influence shape.
        heightMapGenerator.populateChunkHeights(chunkX, chunkZ, heights);
        updateLoadingProgress("Determining Biomes");
        biomeManager.populateChunkBiomes(chunkX, chunkZ, heights, biomes);

        updateLoadingProgress("Applying Biome Materials");
        BitSet wormMask = (nativeCarverCtx != 0L)
            ? nativeWormMask(chunkX, chunkZ, heights)
            : wormCarver.carveMaskForChunk(chunkX, chunkZ, heights);
        CavernCarver.Result cavernResult = cavernCarver.buildForChunk(chunkX, chunkZ, heights);
        MegaCavernCarver.Result megaCavernResult = megaCavernCarver.buildForChunk(chunkX, chunkZ, heights);
        BitSet caveMask = wormMask;
        caveMask.or(cavernResult.carveMask);
        caveMask.or(megaCavernResult.carveMask);
        BitSet formationMask = cavernResult.formationMask;
        formationMask.or(megaCavernResult.formationMask);

        // Native backend: one SIMD volume fill replaces per-block cave-noise
        // sampling in determineBlockType. Null on the Java backend.
        Density3D.Field densityField = density3D.prepareChunk(chunkX, chunkZ, heights);

        // Write terrain into paletted storage directly instead of 65k
        // chunk.setBlock calls (each of which churns dirty flags, per-block
        // state removal, and incremental heightmap updates). AIR cells are
        // skipped entirely — sections above the terrain stay in their
        // near-free uniform tier. The caller recomputes the heightmap once.
        CcoBlockStorage storage = CcoFactory.createEmptyStorage(BlockType.AIR);
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int idx = x * CHUNK_SIZE + z;
                int height = heights[idx];
                BiomeType biome = biomes[idx];
                int worldX = baseX + x;
                int worldZ = baseZ + z;
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    int bit = (x << 12) | (y << 4) | z;
                    BlockType block;
                    if (y > 0 && y < height && formationMask.get(bit)) {
                        block = BlockType.STONE;
                    } else if (y > 0 && y < height && caveMask.get(bit)) {
                        continue; // carved to air — already the uniform fill
                    } else {
                        block = determineBlockType(worldX, y, worldZ, height, biome, densityField, x, z);
                    }
                    if (block != BlockType.AIR) {
                        storage.set(x, y, z, block);
                    }
                }
            }
        }

        Chunk chunk = new Chunk(chunkX, chunkZ, storage);
        // One mesh+data dirty mark replaces the per-setBlock marks. The caller
        // clears data-dirty for waterless chunks, exactly as before.
        chunk.getCcoDirtyTracker().markBlockChanged();
        chunk.setFeaturesPopulated(false);
        return new TerrainResult(chunk, new ColumnProfile(heights, biomes));
    }

    /**
     * Populates features (ores, vegetation, decorations, mobs) on an already-terrained chunk.
     * Caller must ensure neighbor chunks at (+1,0), (0,+1), (+1,+1) exist.
     *
     * @param profile Column profile from terrain generation, or null to recompute
     *                (e.g., for chunks loaded from disk that still need features)
     */
    public void populateChunkWithFeatures(World world, Chunk chunk, SnowLayerManager snowLayerManager,
                                          ColumnProfile profile) {
        if (chunk == null || chunk.areFeaturesPopulated()) {
            return;
        }

        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        if (!verifyNeighborsExist(world, chunkX, chunkZ)) {
            System.err.println("WARNING: populateChunkWithFeatures called before neighbors ready for chunk (" +
                chunkX + ", " + chunkZ + "). Skipping feature population.");
            return;
        }

        updateLoadingProgress("Adding Surface Decorations & Details");

        int[] heights;
        BiomeType[] biomes;
        if (profile != null) {
            // Reuse the profile computed during terrain generation — skips a
            // full noise resampling pass per chunk.
            heights = profile.heights();
            biomes = profile.biomes();
        } else {
            heights = new int[CHUNK_SIZE * CHUNK_SIZE];
            biomes = new BiomeType[CHUNK_SIZE * CHUNK_SIZE];
            heightMapGenerator.populateChunkHeights(chunkX, chunkZ, heights);
            biomeManager.populateChunkBiomes(chunkX, chunkZ, heights, biomes);
        }
        BiomeType dominantBiome = biomes[(CHUNK_SIZE / 2) * CHUNK_SIZE + (CHUNK_SIZE / 2)];

        ChunkGenerationContext ctx = new ChunkGenerationContext(
            world, chunk, snowLayerManager, heights, biomes, dominantBiome);

        oreGenerator.generate(ctx);
        vegetationGenerator.generate(ctx);
        decorationGenerator.generate(ctx);

        // Passive-mob population is owned entirely by EntitySpawner, which is a
        // continuous, visibility-capped cycle (its "single source of truth").
        // The old per-chunk generation spawn below was a second, UNCAPPED path:
        // it rolled a fresh herd for every generated plains chunk with no global
        // cap or density check (and wrote to a different EntityManager than the
        // cap sweep counts against), flooding the world with animals as the
        // player explored. Disabled so EntitySpawner is the sole spawner.
        // MobGenerator.processChunkMobSpawning(world, chunk, dominantBiome, animalRandom, animalRandomLock);

        chunk.setFeaturesPopulated(true);
    }

    private BlockType determineBlockType(int worldX, int y, int worldZ, int height, BiomeType biome,
                                         Density3D.Field densityField, int localX, int localZ) {
        if (y == 0) {
            return BlockType.BEDROCK;
        }
        if (y < height && !((densityField != null)
                ? densityField.isSolid(localX, y, localZ, height, biome)
                : density3D.isSolid(worldX, y, worldZ, height, biome))) {
            return BlockType.AIR;
        }
        if (y < height - 4) {
            if (biome == BiomeType.RED_SAND_DESERT && y < height - 10 &&
                deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "magma", 0.6f)) {
                return BlockType.MAGMA;
            }
            return BlockType.STONE;
        }
        if (y < height - 1) {
            return subsurfaceBlock(biome);
        }
        if (y < height) {
            return surfaceBlock(biome);
        }
        if (y < SEA_LEVEL) {
            if (biome == BiomeType.RED_SAND_DESERT && height > SEA_LEVEL) {
                return BlockType.AIR;
            }
            return BlockType.WATER;
        }
        return BlockType.AIR;
    }

    private static BlockType subsurfaceBlock(BiomeType biome) {
        if (biome == null) return BlockType.DIRT;
        return switch (biome) {
            case RED_SAND_DESERT, BADLANDS -> BlockType.RED_SANDSTONE;
            case DESERT, BEACH -> BlockType.SANDSTONE;
            case PLAINS, SNOWY_PLAINS, TAIGA, MEADOW, TUNDRA -> BlockType.DIRT;
            case STONY_PEAKS -> BlockType.STONE;
            case ICE_FIELDS -> BlockType.ICE;
        };
    }

    private static BlockType surfaceBlock(BiomeType biome) {
        if (biome == null) return BlockType.DIRT;
        return switch (biome) {
            case DESERT, BEACH -> BlockType.SAND;
            case RED_SAND_DESERT, BADLANDS -> BlockType.RED_SAND;
            case PLAINS, MEADOW -> BlockType.GRASS;
            case SNOWY_PLAINS, TAIGA, TUNDRA -> BlockType.SNOWY_DIRT;
            case STONY_PEAKS -> BlockType.STONE;
            case ICE_FIELDS -> BlockType.ICE;
        };
    }

    private boolean verifyNeighborsExist(World world, int chunkX, int chunkZ) {
        return world.hasChunkAt(chunkX + 1, chunkZ) &&
               world.hasChunkAt(chunkX, chunkZ + 1) &&
               world.hasChunkAt(chunkX + 1, chunkZ + 1);
    }

    private void updateLoadingProgress(String stageName) {
        Game game = Game.getInstance();
        if (game != null && game.getLoadingScreen() != null && game.getLoadingScreen().isVisible()) {
            game.getLoadingScreen().updateProgress(stageName);
        }
    }
}
