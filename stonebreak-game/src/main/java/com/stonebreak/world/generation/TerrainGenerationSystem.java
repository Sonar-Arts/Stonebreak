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
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
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

    private final Random animalRandom = new Random();
    private final Object animalRandomLock = new Object();

    public TerrainGenerationSystem(long seed) {
        this.seed = seed;
        this.deterministicRandom = new DeterministicRandom(seed);
        this.heightMapGenerator = new HeightMapGenerator(seed);
        this.biomeManager = new BiomeManager(seed);
        this.oreGenerator = new OreGenerator(deterministicRandom);
        this.vegetationGenerator = new VegetationGenerator(deterministicRandom);
        this.decorationGenerator = new SurfaceDecorationGenerator(deterministicRandom, heightMapGenerator);
    }

    public long getSeed() {
        return seed;
    }

    public float getContinentalnessAt(int x, int z) {
        return heightMapGenerator.getContinentalness(x, z);
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
        heightMapGenerator.populateChunkHeights(chunkX, chunkZ, heights);
        updateLoadingProgress("Determining Biomes");
        biomeManager.populateChunkBiomes(chunkX, chunkZ, biomes);

        updateLoadingProgress("Applying Biome Materials");
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
                    chunk.setBlock(x, y, z, determineBlockType(worldX, y, worldZ, height, biome));
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
        biomeManager.populateChunkBiomes(chunkX, chunkZ, biomes);
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
            case RED_SAND_DESERT -> BlockType.RED_SANDSTONE;
            case DESERT -> BlockType.SANDSTONE;
            case PLAINS, SNOWY_PLAINS -> BlockType.DIRT;
        };
    }

    private static BlockType surfaceBlock(BiomeType biome) {
        if (biome == null) return BlockType.DIRT;
        return switch (biome) {
            case DESERT -> BlockType.SAND;
            case RED_SAND_DESERT -> BlockType.RED_SAND;
            case PLAINS -> BlockType.GRASS;
            case SNOWY_PLAINS -> BlockType.SNOWY_DIRT;
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
