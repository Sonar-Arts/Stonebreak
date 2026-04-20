package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.DeterministicRandom;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.features.OreGenerator;
import com.stonebreak.world.generation.features.SurfaceDecorationGenerator;
import com.stonebreak.world.generation.features.VegetationGenerator;
import com.stonebreak.world.generation.heightmap.Density3D;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
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

    private final Random animalRandom = new Random();
    private final Object animalRandomLock = new Object();

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
     * Generates terrain blocks for a chunk. Features are populated separately
     * once neighbor chunks exist (prevents recursive generation across chunk borders).
     */
    public Chunk generateTerrainOnly(int chunkX, int chunkZ) {
        updateLoadingProgress("Generating Base Terrain Shape");
        Chunk chunk = new Chunk(chunkX, chunkZ);

        int[] heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BiomeType[] biomes = new BiomeType[CHUNK_SIZE * CHUNK_SIZE];

        // Shape first (noise-driven), then skin with biomes. Biomes do not influence shape.
        heightMapGenerator.populateChunkHeights(chunkX, chunkZ, heights);
        updateLoadingProgress("Determining Biomes");
        biomeManager.populateChunkBiomes(chunkX, chunkZ, heights, biomes);

        updateLoadingProgress("Applying Biome Materials");
        BitSet wormMask = wormCarver.carveMaskForChunk(chunkX, chunkZ, heights);
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
                    if (y > 0 && y < height && wormMask.get((x << 12) | (y << 4) | z)) {
                        chunk.setBlock(x, y, z, BlockType.AIR);
                    } else {
                        chunk.setBlock(x, y, z, determineBlockType(worldX, y, worldZ, height, biome));
                    }
                }
            }
        }

        chunk.setFeaturesPopulated(false);
        return chunk;
    }

    /**
     * Populates features (ores, vegetation, decorations, mobs) on an already-terrained chunk.
     * Caller must ensure neighbor chunks at (+1,0), (0,+1), (+1,+1) exist.
     */
    public void populateChunkWithFeatures(World world, Chunk chunk, SnowLayerManager snowLayerManager) {
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

        int[] heights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BiomeType[] biomes = new BiomeType[CHUNK_SIZE * CHUNK_SIZE];
        heightMapGenerator.populateChunkHeights(chunkX, chunkZ, heights);
        biomeManager.populateChunkBiomes(chunkX, chunkZ, heights, biomes);
        BiomeType dominantBiome = biomes[(CHUNK_SIZE / 2) * CHUNK_SIZE + (CHUNK_SIZE / 2)];

        ChunkGenerationContext ctx = new ChunkGenerationContext(
            world, chunk, snowLayerManager, heights, biomes, dominantBiome);

        oreGenerator.generate(ctx);
        vegetationGenerator.generate(ctx);
        decorationGenerator.generate(ctx);

        MobGenerator.processChunkMobSpawning(world, chunk, dominantBiome, animalRandom, animalRandomLock);

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
            case PLAINS, SNOWY_PLAINS, TAIGA, MEADOW -> BlockType.DIRT;
            case TUNDRA, STONY_PEAKS -> BlockType.STONE;
            case ICE_FIELDS -> BlockType.ICE;
        };
    }

    private static BlockType surfaceBlock(BiomeType biome) {
        if (biome == null) return BlockType.DIRT;
        return switch (biome) {
            case DESERT, BEACH -> BlockType.SAND;
            case RED_SAND_DESERT, BADLANDS -> BlockType.RED_SAND;
            case PLAINS, MEADOW -> BlockType.GRASS;
            case SNOWY_PLAINS, TAIGA -> BlockType.SNOWY_DIRT;
            case TUNDRA -> BlockType.GRAVEL;
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
