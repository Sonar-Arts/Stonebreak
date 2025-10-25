package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.biomes.BiomeBlendResult;
import com.stonebreak.world.generation.biomes.BiomeBlender;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.features.OreGenerator;
import com.stonebreak.world.generation.features.SurfaceDecorationGenerator;
import com.stonebreak.world.generation.features.VegetationGenerator;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.DeterministicRandom;

import java.util.Random;

/**
 * Orchestrates terrain generation by delegating to specialized subsystems.
 * This class follows the Controller pattern - coordinating various generators
 * without containing detailed generation logic.
 *
 * Enhancements:
 * - Phase 1: Biome-specific height variations for distinct terrain characteristics
 * - Phase 2: Whittaker diagram biome classification for ecological accuracy
 * - Phase 3: Biome blending for smooth, natural transitions
 *
 * Refactored to follow SOLID principles with modular, single-responsibility components.
 */
public class TerrainGenerationSystem {
    public static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    public static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    // Subsystem generators
    private final HeightMapGenerator heightMapGenerator;
    private final BiomeManager biomeManager;
    private final BiomeBlender biomeBlender;
    private final OreGenerator oreGenerator;
    private final VegetationGenerator vegetationGenerator;
    private final SurfaceDecorationGenerator decorationGenerator;

    // Progress reporting
    private final LoadingProgressReporter progressReporter;

    // Legacy fields maintained for compatibility
    private final long seed;
    private final Random animalRandom;
    private final DeterministicRandom deterministicRandom;
    private final Object animalRandomLock = new Object();

    /**
     * Creates a new terrain generation system with the given seed.
     * Initializes all subsystem generators with default configuration.
     * Uses null progress reporter (no progress updates).
     *
     * @param seed World seed for deterministic generation
     */
    public TerrainGenerationSystem(long seed) {
        this(seed, TerrainGenerationConfig.defaultConfig(), LoadingProgressReporter.NULL);
    }

    /**
     * Creates a new terrain generation system with the given seed and configuration.
     * Initializes all subsystem generators with the provided configuration.
     * Uses null progress reporter (no progress updates).
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public TerrainGenerationSystem(long seed, TerrainGenerationConfig config) {
        this(seed, config, LoadingProgressReporter.NULL);
    }

    /**
     * Creates a new terrain generation system with the given seed, configuration, and progress reporter.
     * Initializes all subsystem generators with the provided configuration.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     * @param progressReporter Progress reporter for loading screen updates
     */
    public TerrainGenerationSystem(long seed, TerrainGenerationConfig config, LoadingProgressReporter progressReporter) {
        this.seed = seed;
        this.animalRandom = new Random(); // Use current time for truly random animal spawning
        this.deterministicRandom = new DeterministicRandom(seed);
        this.progressReporter = progressReporter;

        // Initialize specialized generators with injected configuration
        this.heightMapGenerator = new HeightMapGenerator(seed, config);
        this.biomeManager = new BiomeManager(seed, config);
        this.biomeBlender = new BiomeBlender(config);
        this.oreGenerator = new OreGenerator(seed);
        this.vegetationGenerator = new VegetationGenerator(seed);
        this.decorationGenerator = new SurfaceDecorationGenerator(seed);
    }

    /**
     * Gets the biome manager for biome queries.
     * Consumers can directly query biomes, temperature, and moisture.
     *
     * @return The biome manager
     */
    public BiomeManager getBiomeManager() {
        return biomeManager;
    }

    /**
     * Gets the height map generator for height queries.
     * Consumers can directly query terrain height.
     *
     * @return The height map generator
     */
    public HeightMapGenerator getHeightMapGenerator() {
        return heightMapGenerator;
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
     * Updates loading progress during world generation.
     * Delegates to the injected progress reporter.
     *
     * @param stageName Name of the current generation stage
     */
    private void updateLoadingProgress(String stageName) {
        progressReporter.updateProgress(stageName);
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

                // Update progress for biome determination
                if (x == 0 && z == 0) {
                    updateLoadingProgress("Determining Biomes & Blending");
                }

                // Phase 1: Altitude-based temperature for realistic mountain snow
                // Phase 3: Generate blended height for smooth biome transitions
                // Architecture: Base Height (continentalness) + Biome Modifier + Blending + Altitude Chill
                int baseHeight = heightMapGenerator.generateHeight(worldX, worldZ);
                BiomeBlendResult blendResult = biomeBlender.getBlendedBiomeAtHeight(biomeManager, worldX, worldZ, baseHeight);
                int height = heightMapGenerator.generateBlendedHeight(baseHeight, blendResult, worldX, worldZ);

                // Get dominant biome for block type determination
                BiomeType biome = blendResult.getDominantBiome();

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
     * Phase 4: Expanded to handle 10 biomes with appropriate subsurface materials.
     *
     * @param biome Biome type
     * @return Subsurface block type
     */
    private BlockType getSubsurfaceBlock(BiomeType biome) {
        if (biome == null) return BlockType.DIRT;
        return switch (biome) {
            // Original 4 biomes
            case RED_SAND_DESERT -> BlockType.RED_SANDSTONE;
            case DESERT -> BlockType.SANDSTONE;
            case PLAINS, SNOWY_PLAINS -> BlockType.DIRT;

            // Phase 4: New biomes
            case TUNDRA -> BlockType.STONE;  // Frozen permafrost bedrock
            case TAIGA -> BlockType.DIRT;  // Forest soil
            case STONY_PEAKS -> BlockType.STONE;  // Solid rock mountains
            case GRAVEL_BEACH -> BlockType.SAND;  // Sandy subsurface beneath gravel
            case ICE_FIELDS -> BlockType.ICE;  // Deep glacial ice layers
            case BADLANDS -> BlockType.RED_SANDSTONE;  // Sedimentary layers beneath badlands
        };
    }

    /**
     * Gets the surface block type for a biome.
     *
     * Phase 4: Expanded to handle 10 biomes with mixed block types where applicable.
     *
     * @param biome Biome type
     * @return Surface block type
     */
    private BlockType getSurfaceBlock(BiomeType biome) {
        if (biome == null) return BlockType.DIRT;
        return switch (biome) {
            // Original 4 biomes
            case DESERT -> BlockType.SAND;
            case RED_SAND_DESERT -> BlockType.RED_SAND;
            case PLAINS -> BlockType.GRASS;
            case SNOWY_PLAINS -> BlockType.SNOWY_DIRT;

            // Phase 4: New biomes
            case TUNDRA -> BlockType.GRAVEL;  // Permafrost-like gravel surface
            case TAIGA -> BlockType.SNOWY_DIRT;  // Snow-covered forest floor
            case STONY_PEAKS -> BlockType.STONE;  // Exposed rocky peaks
            case GRAVEL_BEACH -> BlockType.GRAVEL;  // Gravel shoreline (mixed with sand in decorations)
            case ICE_FIELDS -> BlockType.ICE;  // Glacial ice surface
            case BADLANDS -> BlockType.RED_SAND;  // Eroded red sand mesas
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

    // ========================================
    // Public API - Terrain Query Methods
    // ========================================
    // These methods provide a unified interface for querying terrain properties.
    // They delegate to the appropriate subsystem generators (BiomeManager, HeightMapGenerator).

    /**
     * Gets the continentalness value at the specified world position.
     * Continentalness determines whether terrain is ocean, coast, or land.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Continentalness value in range [-1.0, 1.0]
     */
    public float getContinentalnessAt(int x, int z) {
        return heightMapGenerator.getContinentalness(x, z);
    }

    /**
     * Gets the biome type at the specified world position.
     * Uses sea level for temperature calculation.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return The biome type at the given position
     */
    public BiomeType getBiomeType(int x, int z) {
        return biomeManager.getBiome(x, z);
    }

    /**
     * Gets the moisture value at the specified world position.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Moisture value in range [0.0, 1.0]
     */
    public float generateMoisture(int x, int z) {
        return biomeManager.getMoisture(x, z);
    }

    /**
     * Gets the temperature value at the specified world position.
     * Uses sea level for temperature calculation.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Temperature value in range [0.0, 1.0]
     */
    public float generateTemperature(int x, int z) {
        return biomeManager.getTemperature(x, z);
    }

    /**
     * Gets the erosion noise value at the specified world position.
     * Erosion adds subtle terrain variation (weathering effects).
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Erosion noise value in range approximately [-0.3, 0.3]
     */
    public float getErosionNoiseAt(int x, int z) {
        return heightMapGenerator.getErosionNoiseValue(x, z);
    }

    /**
     * Gets the base terrain height before biome modifiers and erosion.
     * This is just the continentalness-based height.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Base terrain height (clamped to world bounds)
     */
    public int getBaseHeightBeforeErosion(int x, int z) {
        return heightMapGenerator.getBaseHeightBeforeErosion(x, z);
    }

    /**
     * Gets the terrain height with biome modifiers but before erosion.
     * Useful for debugging to see erosion's effect.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Terrain height with biome modifier but before erosion
     */
    public int getHeightBeforeErosion(int x, int z) {
        int baseHeight = heightMapGenerator.generateHeight(x, z);
        BiomeType biome = biomeManager.getBiome(x, z);
        return heightMapGenerator.applyBiomeModifier(baseHeight, biome, x, z);
    }

    /**
     * Gets the actual final terrain height as used in world generation.
     * This is the final height including biome modifiers and erosion.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Final terrain height used in generation
     */
    public int getFinalTerrainHeight(int x, int z) {
        int baseHeight = heightMapGenerator.generateHeight(x, z);
        BiomeBlendResult blendResult = biomeBlender.getBlendedBiomeAtHeight(biomeManager, x, z, baseHeight);
        return heightMapGenerator.generateBlendedHeight(baseHeight, blendResult, x, z);
    }
}