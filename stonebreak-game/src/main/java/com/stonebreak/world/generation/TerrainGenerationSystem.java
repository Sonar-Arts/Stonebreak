package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.features.OreGenerator;
import com.stonebreak.world.generation.features.SurfaceDecorationGenerator;
import com.stonebreak.world.generation.features.VegetationGenerator;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.generation.terrain.MobGenerator;
import com.stonebreak.world.DeterministicRandom;

import java.util.Random;

/**
 * Orchestrates terrain generation by delegating to specialized subsystems.
 * This class follows the Controller pattern - coordinating various generators
 * without containing detailed generation logic.
 *
 * Refactored to follow SOLID principles with modular, single-responsibility components.
 */
public class TerrainGenerationSystem {
    public static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    public static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    // Subsystem generators
    private final HeightMapGenerator heightMapGenerator;
    private final BiomeManager biomeManager;
    private final OreGenerator oreGenerator;
    private final VegetationGenerator vegetationGenerator;
    private final SurfaceDecorationGenerator decorationGenerator;

    // Legacy fields maintained for compatibility
    private final long seed;
    private final Random animalRandom;
    private final DeterministicRandom deterministicRandom;
    private final Object animalRandomLock = new Object();

    /**
     * Creates a new terrain generation system with the given seed.
     * Initializes all subsystem generators.
     *
     * @param seed World seed for deterministic generation
     */
    public TerrainGenerationSystem(long seed) {
        this.seed = seed;
        this.animalRandom = new Random(); // Use current time for truly random animal spawning
        this.deterministicRandom = new DeterministicRandom(seed);

        // Initialize specialized generators
        this.heightMapGenerator = new HeightMapGenerator(seed);
        this.biomeManager = new BiomeManager(seed);
        this.oreGenerator = new OreGenerator(seed);
        this.vegetationGenerator = new VegetationGenerator(seed);
        this.decorationGenerator = new SurfaceDecorationGenerator(seed);
    }

    /**
     * Generates terrain height for the specified world position.
     * Delegates to HeightMapGenerator.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Terrain height at the given position
     */
    public int generateTerrainHeight(int x, int z) {
        return heightMapGenerator.generateHeight(x, z);
    }

    /**
     * Gets the continentalness value at the specified world position.
     * Delegates to HeightMapGenerator.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Continentalness value in range [-1.0, 1.0]
     */
    public float getContinentalnessAt(int x, int z) {
        return heightMapGenerator.getContinentalness(x, z);
    }

    /**
     * Generates moisture value for determining biomes.
     * Delegates to BiomeManager.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Moisture value in range [0.0, 1.0]
     */
    public float generateMoisture(int x, int z) {
        return biomeManager.getMoisture(x, z);
    }

    /**
     * Generates temperature value for determining biomes.
     * Delegates to BiomeManager.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Temperature value in range [0.0, 1.0]
     */
    public float generateTemperature(int x, int z) {
        return biomeManager.getTemperature(x, z);
    }

    /**
     * Determines the biome type based on temperature and moisture values.
     * Delegates to BiomeManager.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return The biome type at the given position
     */
    public BiomeType getBiomeType(int x, int z) {
        return biomeManager.getBiome(x, z);
    }

    /**
     * Gets the world seed.
     *
     * @return World seed
     */
    public long getSeed() {
        return seed;
    }

    /**
     * Gets the random instance for animal spawning (truly random, not deterministic).
     *
     * @return Random instance for animal spawning
     */
    public Random getAnimalRandom() {
        return animalRandom;
    }

    /**
     * Gets the random lock for animal spawning synchronization.
     *
     * @return Object used as synchronization lock
     */
    public Object getAnimalRandomLock() {
        return animalRandomLock;
    }

    /**
     * Updates loading progress during world generation.
     */
    private void updateLoadingProgress(String stageName) {
        Game game = Game.getInstance();
        if (game != null && game.getLoadingScreen() != null && game.getLoadingScreen().isVisible()) {
            game.getLoadingScreen().updateProgress(stageName);
        }
    }
    
    /**
     * Generates terrain-only chunk (no features like ores or trees yet).
     * Features are populated separately after the chunk is registered in the chunk store
     * to avoid recursive chunk generation when features span multiple chunks.
     * Uses chunk.setBlock() which internally uses CCO API for block operations.
     * Mesh generation happens automatically via CCO dirty tracking when chunk is rendered.
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Generated chunk with terrain (no features yet)
     */
    public Chunk generateTerrainOnly(int chunkX, int chunkZ) {
        updateLoadingProgress("Generating Base Terrain Shape");
        Chunk chunk = new Chunk(chunkX, chunkZ);

        // Generate terrain - chunk.setBlock() uses CCO BlockWriter internally
        for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
            for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                // Calculate absolute world coordinates
                int worldX = chunkX * WorldConfiguration.CHUNK_SIZE + x;
                int worldZ = chunkZ * WorldConfiguration.CHUNK_SIZE + z;

                // Delegate height generation to HeightMapGenerator
                int height = heightMapGenerator.generateHeight(worldX, worldZ);

                // Update progress for biome determination
                if (x == 0 && z == 0) {
                    updateLoadingProgress("Determining Biomes");
                }
                // Delegate biome determination to BiomeManager
                BiomeType biome = biomeManager.getBiome(worldX, worldZ);

                // Generate blocks based on height and biome
                if (x == 8 && z == 8) { // Update progress mid-chunk
                    updateLoadingProgress("Applying Biome Materials");
                }
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    BlockType blockType = determineBlockType(worldX, y, worldZ, height, biome);
                    chunk.setBlock(x, y, z, blockType); // Uses CCO BlockWriter internally
                }
            }
        }

        // Features will be populated after chunk registration to avoid recursion
        chunk.setFeaturesPopulated(false);

        // Mesh generation happens automatically via CCO dirty tracking when chunk is rendered
        return chunk;
    }

    /**
     * Determines the block type for a given position based on height and biome.
     * Handles bedrock, stone layers, subsurface, surface, water, and air.
     *
     * @param worldX World X coordinate
     * @param y World Y coordinate
     * @param worldZ World Z coordinate
     * @param height Terrain height at this column
     * @param biome Biome type
     * @return Block type for this position
     */
    private BlockType determineBlockType(int worldX, int y, int worldZ, int height, BiomeType biome) {
        if (y == 0) {
            return BlockType.BEDROCK;
        } else if (y < height - 4) {
            // Deeper layers - biome can influence stone type
            if (biome == BiomeType.RED_SAND_DESERT && y < height - 10 &&
                deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "magma", 0.6f)) {
                return BlockType.MAGMA; // More magma deeper in volcanic areas
            }
            return BlockType.STONE;
        } else if (y < height - 1) {
            // Sub-surface layer
            return getSubsurfaceBlock(biome);
        } else if (y < height) {
            // Top layer
            return getSurfaceBlock(biome);
        } else if (y < SEA_LEVEL) {
            // Water level - no water in volcanic biomes above sea level
            if (biome == BiomeType.RED_SAND_DESERT && height > SEA_LEVEL) {
                return BlockType.AIR;
            }
            return BlockType.WATER;
        } else {
            return BlockType.AIR;
        }
    }

    /**
     * Gets the subsurface block type for a biome.
     *
     * @param biome Biome type
     * @return Subsurface block type
     */
    private BlockType getSubsurfaceBlock(BiomeType biome) {
        if (biome == null) return BlockType.DIRT;
        return switch (biome) {
            case RED_SAND_DESERT -> BlockType.RED_SANDSTONE;
            case DESERT -> BlockType.SANDSTONE;
            case PLAINS, SNOWY_PLAINS -> BlockType.DIRT;
        };
    }

    /**
     * Gets the surface block type for a biome.
     *
     * @param biome Biome type
     * @return Surface block type
     */
    private BlockType getSurfaceBlock(BiomeType biome) {
        if (biome == null) return BlockType.DIRT;
        return switch (biome) {
            case DESERT -> BlockType.SAND;
            case RED_SAND_DESERT -> BlockType.RED_SAND;
            case PLAINS -> BlockType.GRASS;
            case SNOWY_PLAINS -> BlockType.SNOWY_DIRT;
        };
    }

    /**
     * Populates an existing chunk with features like ores and trees.
     * Delegates to specialized feature generators for clean separation of concerns.
     * Mesh regeneration happens automatically via CCO dirty tracking.
     *
     * IMPORTANT: This method assumes required neighbor chunks exist (at least terrain generated).
     * WorldChunkStore ensures this before calling this method.
     *
     * @param world The world instance
     * @param chunk The chunk to populate with features
     * @param snowLayerManager Manager for snow layer tracking
     */
    public void populateChunkWithFeatures(World world, Chunk chunk, SnowLayerManager snowLayerManager) {
        if (chunk == null || chunk.areFeaturesPopulated()) {
            return; // Already populated or null chunk
        }

        updateLoadingProgress("Adding Surface Decorations & Details");

        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();

        // Verify required neighbors exist (defensive check)
        if (!verifyNeighborsExist(world, chunkX, chunkZ)) {
            System.err.println("WARNING: populateChunkWithFeatures called before neighbors ready for chunk (" +
                chunkX + ", " + chunkZ + "). Skipping feature population.");
            return;
        }

        // Determine chunk's dominant biome (center position)
        BiomeType chunkBiome = biomeManager.getBiome(
            chunkX * WorldConfiguration.CHUNK_SIZE + WorldConfiguration.CHUNK_SIZE / 2,
            chunkZ * WorldConfiguration.CHUNK_SIZE + WorldConfiguration.CHUNK_SIZE / 2
        );

        // Delegate feature generation to specialized generators
        // Each generator handles its own surface height calculations and biome checks
        oreGenerator.generateOres(world, chunk, chunkBiome, chunkX, chunkZ, WORLD_HEIGHT);
        vegetationGenerator.generateVegetation(world, chunk, chunkBiome, chunkX, chunkZ);
        decorationGenerator.generateDecorations(world, chunk, chunkBiome, chunkX, chunkZ, snowLayerManager);

        // Process mob spawning for this chunk
        MobGenerator.processChunkMobSpawning(world, chunk, chunkBiome, animalRandom, animalRandomLock);

        // Mesh regeneration happens automatically via CCO dirty tracking when chunk is rendered
        chunk.setFeaturesPopulated(true);
    }

    /**
     * Verifies that required neighbor chunks exist for safe feature population.
     * Follows Minecraft's pattern: requires chunks at (x+1, z), (x, z+1), (x+1, z+1).
     *
     * @param world The world to check
     * @param chunkX Current chunk X coordinate
     * @param chunkZ Current chunk Z coordinate
     * @return true if all required neighbors exist
     */
    private boolean verifyNeighborsExist(World world, int chunkX, int chunkZ) {
        // Check required neighbors (Minecraft-style dependency)
        return world.hasChunkAt(chunkX + 1, chunkZ) &&      // East
               world.hasChunkAt(chunkX, chunkZ + 1) &&      // South
               world.hasChunkAt(chunkX + 1, chunkZ + 1);    // Southeast
    }
}