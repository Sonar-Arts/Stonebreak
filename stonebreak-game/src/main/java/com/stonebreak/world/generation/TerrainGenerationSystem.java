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
import com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig;
import com.stonebreak.world.generation.diffusion.DiffusionTileCache;
import com.stonebreak.world.generation.diffusion.TerrainTileSource;
import com.stonebreak.world.generation.diffusion.process.TerrainServiceProcessManager;
import com.stonebreak.world.generation.features.OreGenerator;
import com.stonebreak.world.generation.features.SurfaceDecorationGenerator;
import com.stonebreak.world.generation.features.VegetationGenerator;
import com.stonebreak.world.generation.heightmap.Density3D;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.heightmap.CavernCarver;
import com.stonebreak.world.generation.heightmap.MegaCavernCarver;
import com.stonebreak.world.generation.heightmap.PerlinWormCarver;

import java.util.BitSet;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
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

    public TerrainGenerationSystem(long seed) {
        this(withServicesRunning(seed), new DiffusionTileCache(DiffusionBridgeConfig.fromSystemProperties(), seed));
    }

    /**
     * Blocks until the local terrain-diffusion services are up and pinned to {@code seed}
     * (starting/restarting them if needed — see {@link TerrainServiceProcessManager}), then
     * returns the seed unchanged. A pass-through so it can sit in the constructor-delegation
     * chain above without a separate init block.
     */
    private static long withServicesRunning(long seed) {
        TerrainServiceProcessManager.getInstance().ensureRunningForSeed(seed);
        return seed;
    }

    /**
     * Test-only seam: injects a fake {@link TerrainTileSource} instead of the
     * real HTTP-backed bridge client, so terrain-shape logic (cave carving,
     * mesh consistency, etc.) can be exercised offline. Production code must
     * always go through {@link #TerrainGenerationSystem(long)} — no fallback
     * path, see plan.md Phase 2.
     */
    TerrainGenerationSystem(long seed, TerrainTileSource tileSource) {
        this.seed = seed;
        this.deterministicRandom = new DeterministicRandom(seed);
        this.heightMapGenerator = new HeightMapGenerator(tileSource);
        this.biomeManager = new BiomeManager(tileSource);
        this.oreGenerator = new OreGenerator(deterministicRandom);
        this.vegetationGenerator = new VegetationGenerator(deterministicRandom);
        this.decorationGenerator = new SurfaceDecorationGenerator(deterministicRandom, heightMapGenerator, seed);
        this.density3D = new Density3D(seed);
        this.wormCarver = new PerlinWormCarver(seed, heightMapGenerator);
        this.cavernCarver = new CavernCarver(seed, heightMapGenerator);
        this.megaCavernCarver = new MegaCavernCarver(seed, heightMapGenerator);
        this.wormCarver.setCavernCarver(cavernCarver);
        this.wormCarver.setMegaCavernCarver(megaCavernCarver);
    }

    public long getSeed() {
        return seed;
    }

    public BiomeType getBiomeAt(int x, int z) {
        return biomeManager.getBiome(x, z);
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
     * Returns the surface block a column places at its terrain top
     * ({@code y == height - 1}), derived from the same biome rules as
     * {@link #determineBlockType}. Submerged columns report their real seabed
     * block — {@code determineBlockType} places {@code surfaceBlock(biome)}
     * there just like on land — so coarse renderers (FastLOD) can draw the
     * ocean floor; submergence itself is decided from {@code height < SEA_LEVEL}.
     */
    public BlockType getSurfaceBlockAt(int worldX, int worldZ) {
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
     * indexed {@code [ix*count + iz]}. Results are identical to
     * {@link #getFinalTerrainHeightAt}, {@link #getSurfaceBlockAt} and
     * {@link #getTreeAt} at the same coordinates.
     *
     * <p>On the diffusion generator the batching win lives one layer down: the
     * heights come from bridge tiles that {@code DiffusionTileCache} already
     * serves whole, so a straight per-column loop touches each tile once and
     * needs no separate grid-fill path.
     *
     * @param outSurface nullable; length count*count when present
     * @param outTrees   nullable; length count*count when present
     */
    public void sampleColumns(int worldX0, int worldZ0, int count, int stride,
                              int[] outHeights,
                              BlockType[] outSurface,
                              com.stonebreak.world.generation.features.VegetationGenerator.TreeSample[] outTrees) {
        boolean needBiomes = outSurface != null || outTrees != null;
        for (int ix = 0; ix < count; ix++) {
            for (int iz = 0; iz < count; iz++) {
                int idx = ix * count + iz;
                int wx = worldX0 + ix * stride;
                int wz = worldZ0 + iz * stride;
                int height = heightMapGenerator.generateHeight(wx, wz);
                outHeights[idx] = height;
                if (!needBiomes) {
                    continue;
                }
                // Biome is resolved for submerged columns too — their surface
                // is the real seabed block (see getSurfaceBlockAt).
                BiomeType biome = biomeManager.getBiome(wx, wz);
                BlockType surface = surfaceBlock(biome);
                if (outSurface != null) {
                    outSurface[idx] = surface;
                }
                if (outTrees != null) {
                    outTrees[idx] = (height < SEA_LEVEL) ? null
                        : com.stonebreak.world.generation.features.VegetationGenerator.probeTree(
                            wx, wz, biome, surface, deterministicRandom);
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
        BitSet wormMask = wormCarver.carveMaskForChunk(chunkX, chunkZ, heights);
        CavernCarver.Result cavernResult = cavernCarver.buildForChunk(chunkX, chunkZ, heights);
        MegaCavernCarver.Result megaCavernResult = megaCavernCarver.buildForChunk(chunkX, chunkZ, heights);
        BitSet caveMask = wormMask;
        caveMask.or(cavernResult.carveMask);
        caveMask.or(megaCavernResult.carveMask);
        BitSet formationMask = cavernResult.formationMask;
        formationMask.or(megaCavernResult.formationMask);

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
                    int bit = LocalBlockKey.pack(x, y, z);
                    BlockType block;
                    if (y > 0 && y < height && formationMask.get(bit)) {
                        block = BlockType.STONE;
                    } else if (y > 0 && y < height && caveMask.get(bit)) {
                        continue; // carved to air — already the uniform fill
                    } else {
                        block = determineBlockType(worldX, y, worldZ, height, biome);
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

    private BlockType determineBlockType(int worldX, int y, int worldZ, int height, BiomeType biome) {
        if (y == 0) {
            return BlockType.BEDROCK;
        }
        if (y < height && !density3D.isSolid(worldX, y, worldZ, height, biome)) {
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
