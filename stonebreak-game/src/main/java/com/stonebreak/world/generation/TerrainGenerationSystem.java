package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeTerrainModifierRegistry;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.caves.CaveNoiseGenerator;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.config.WaterGenerationConfig;
import com.stonebreak.world.generation.water.BasinWaterFiller;
import com.stonebreak.world.generation.features.OreGenerator;
import com.stonebreak.world.generation.features.SurfaceDecorationGenerator;
import com.stonebreak.world.generation.features.VegetationGenerator;
import com.stonebreak.world.generation.terrain.TerrainFeatureRegistry;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.DeterministicRandom;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Random;

/**
 * Orchestrates terrain generation by delegating to specialized subsystems.
 * This class follows the Controller pattern - coordinating various generators
 * without containing detailed generation logic.
 *
 * Multi-Noise System + Two-Pass Generation:
 * - Terrain generates independently from biomes (continentalness + erosion + PV + weirdness)
 * - Biomes selected using 6 parameters (adds temperature + humidity to terrain parameters)
 * - Same biome can appear on varied terrain (flat deserts AND hilly deserts)
 * - Rare biome variants via weirdness parameter
 *
 * Generation Order (Two-Pass System):
 * 1. Sample multi-noise parameters (6D point in parameter space)
 * 2. PASS 1: Generate terrain height with hints (mesa terracing, peak sharpening, etc.)
 * 3. Select biome from parameters (biome adapts to terrain)
 * 4. PASS 2: Apply biome-specific modifiers (canyon carving, hoodoos, dunes, etc.)
 * 5. Apply surface materials based on biome
 * 6. Populate features (ores, vegetation, decorations)
 *
 * Refactored to follow SOLID principles with modular, single-responsibility components.
 */
public class TerrainGenerationSystem {
    public static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    public static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    // Subsystem generators
    private final TerrainGenerator terrainGenerator;
    private final BiomeManager biomeManager;
    private final BiomeTerrainModifierRegistry modifierRegistry;  // Phase 2: Biome-specific modifiers
    private final CaveNoiseGenerator caveGenerator;  // Ridged noise-based cave system
    private final BasinWaterFiller basinWaterFiller;  // Dynamic multi-elevation water bodies
    private final OreGenerator oreGenerator;
    private final VegetationGenerator vegetationGenerator;
    private final SurfaceDecorationGenerator decorationGenerator;
    private final TerrainFeatureRegistry terrainFeatureRegistry;

    // Progress reporting
    private final LoadingProgressReporter progressReporter;

    // Legacy fields maintained for compatibility
    private final long seed;
    private final Random animalRandom;
    private final DeterministicRandom deterministicRandom;

    /**
     * Creates a new terrain generation system with the given seed, configuration, generator type, and progress reporter.
     * Initializes all subsystem generators with the provided configuration.
     *
     * Multi-Noise System: Terrain and biomes generate independently using shared parameters.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     * @param generatorType Terrain generator type (e.g., "HYBRID_SDF", "SPLINE")
     * @param progressReporter Progress reporter for loading screen updates
     */
    public TerrainGenerationSystem(long seed, TerrainGenerationConfig config, String generatorType, LoadingProgressReporter progressReporter) {
        this.seed = seed;
        this.animalRandom = new Random(); // Use current time for truly random animal spawning
        this.deterministicRandom = new DeterministicRandom(seed);
        this.progressReporter = progressReporter;

        // Initialize specialized generators with injected configuration
        // Multi-Noise System: TerrainGenerator and BiomeManager use shared NoiseRouter (via BiomeManager)
        this.terrainGenerator = TerrainGeneratorFactory.createFromString(generatorType, seed, config, true);
        this.biomeManager = new BiomeManager(seed, config);
        this.modifierRegistry = new BiomeTerrainModifierRegistry(seed);  // Phase 2: Initialize modifier registry
        this.caveGenerator = new CaveNoiseGenerator(seed);  // Ridged noise cave system (cheese + spaghetti)

        // Initialize dynamic water generation system
        WaterGenerationConfig waterConfig = new WaterGenerationConfig();
        this.basinWaterFiller = new BasinWaterFiller(
                biomeManager.getNoiseRouter(),
                terrainGenerator,
                caveGenerator,
                waterConfig
        );

        this.oreGenerator = new OreGenerator(seed);
        this.vegetationGenerator = new VegetationGenerator(seed, biomeManager.getVariationRouter());
        this.decorationGenerator = new SurfaceDecorationGenerator(seed);

        // Initialize terrain feature registry with default features (overhangs, arches)
        // Note: CaveSystemFeature is now replaced by integrated cave density
        this.terrainFeatureRegistry = TerrainFeatureRegistry.withDefaults(seed);
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
     * MULTI-NOISE SYSTEM + TWO-PASS GENERATION:
     * 1. Sample parameters (continentalness, erosion, PV, weirdness, temperature, humidity)
     * 2. PASS 1: Generate base height with terrain hints (mesa, peaks, hills, plains)
     * 3. Select biome from parameters (biome adapts to terrain)
     * 4. PASS 2: Apply biome-specific modifiers (canyons, hoodoos, dunes, outcrops)
     * 5. Apply surface materials based on biome
     *
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Generated chunk with terrain (no features yet)
     */
    public Chunk generateTerrainOnly(int chunkX, int chunkZ) {
        // Wrap terrain generation with ScopedValue for JDK 25 optimization
        // This binding is required for DeterministicRandom convenience methods
        final Chunk[] chunkHolder = new Chunk[1];
        DeterministicRandom.runWithScopedRandom(() -> {
            chunkHolder[0] = generateTerrainOnlyImpl(chunkX, chunkZ);
        });
        return chunkHolder[0];
    }

    /**
     * Internal implementation of terrain generation (wrapped by ScopedValue binding).
     */
    private Chunk generateTerrainOnlyImpl(int chunkX, int chunkZ) {
        updateLoadingProgress("Generating Multi-Noise Terrain");
        Chunk chunk = new Chunk(chunkX, chunkZ);

        // Extract constants for performance (reduces field access and multiplication overhead)
        final int chunkSize = WorldConfiguration.CHUNK_SIZE;
        final int chunkWorldX = chunkX * chunkSize;
        final int chunkWorldZ = chunkZ * chunkSize;

        // Initialize surface height cache (16x16 array, built during generation)
        int[][] surfaceHeightCache = new int[chunkSize][chunkSize];
        // Initialize with -1 (no surface found yet)
        for (int x = 0; x < chunkSize; x++) {
            java.util.Arrays.fill(surfaceHeightCache[x], -1);
        }

        // Cache terrain heights, biomes, and noise parameters for water fill (avoids recalculation)
        int[][] terrainHeights = new int[chunkSize][chunkSize];
        BiomeType[][] biomeCache = new BiomeType[chunkSize][chunkSize];
        MultiNoiseParameters[][] paramsCache = new MultiNoiseParameters[chunkSize][chunkSize];

        // Generate terrain using multi-noise system
        for (int x = 0; x < chunkSize; x++) {
            for (int z = 0; z < chunkSize; z++) {
                // Calculate absolute world coordinates
                int worldX = chunkWorldX + x;
                int worldZ = chunkWorldZ + z;

                // Sample multi-noise parameters (interpolated for 94% reduction)
                MultiNoiseParameters params = biomeManager.getNoiseRouter().sampleInterpolatedParameters(worldX, worldZ, SEA_LEVEL);

                // PASS 1: Generate base terrain height
                int baseHeight = terrainGenerator.generateHeight(worldX, worldZ, params);

                // Select biome using sampled parameters
                BiomeType biome = biomeManager.getBiomeAtHeight(worldX, worldZ, baseHeight);

                // PASS 2: Apply biome-specific terrain modifiers
                int height = modifierRegistry.applyModifier(biome, baseHeight, params, worldX, worldZ);

                // Cache for water fill (eliminates redundant recalculation)
                terrainHeights[x][z] = height;
                biomeCache[x][z] = biome;
                paramsCache[x][z] = params;

                // Track actual surface height for this column (updated as we generate)
                int actualSurfaceY = -1;

                // Generate blocks for this column (bottom-up for cache efficiency)
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    // Check if below height (default solid)
                    boolean shouldBeSolid = y < height;

                    // Check cave density if potentially underground
                    // Caves are integrated into terrain generation (Minecraft 1.18+ approach)
                    if (shouldBeSolid && caveGenerator.canGenerateCaves(y)) {
                        float caveDensity = caveGenerator.sampleCaveDensity(worldX, y, worldZ, height);
                        // High cave density carves out the block
                        if (caveDensity > 0.0f) {
                            shouldBeSolid = false; // Cave removes this block
                        }
                    }

                    // Check other terrain features (overhangs, arches) if still solid
                    if (shouldBeSolid) {
                        boolean shouldRemove = terrainFeatureRegistry.shouldRemoveBlock(worldX, y, worldZ, height, biome);
                        if (shouldRemove) {
                            shouldBeSolid = false; // Feature removes this block
                        }
                    }

                    if (!shouldBeSolid) {
                        // Block is air - water will be filled via flood-fill after terrain generation
                        // This ensures caves stay dry while ocean water fills properly
                        chunk.setBlock(x, y, z, BlockType.AIR);
                        continue;
                    }

                    // If solid, determine the material type based on biome
                    // Biome only affects materials, NOT height
                    BlockType blockType = determineBlockType(worldX, y, worldZ, height, biome);
                    chunk.setBlock(x, y, z, blockType); // Uses CCO BlockWriter internally

                    // Update surface height (track highest solid non-water block)
                    if (blockType != BlockType.WATER) {
                        actualSurfaceY = y;
                    }
                }

                // Store surface height for this column
                surfaceHeightCache[x][z] = actualSurfaceY;
            }
        }

        // Set surface height cache (eliminates ~131,000 block accesses during feature generation)
        chunk.setSurfaceHeightCache(surfaceHeightCache);

        // Fill water bodies using basin detection (keeps caves dry)
        // Pass cached data to avoid recalculating heights/biomes/parameters
        updateLoadingProgress("Filling Water Bodies");
        basinWaterFiller.fillWaterBodies(chunk, terrainHeights, biomeCache, paramsCache);

        // Features will be populated after chunk registration to avoid recursion
        chunk.setFeaturesPopulated(false);

        // Clear parameter interpolation cache (~5-10 entries per chunk)
        biomeManager.getNoiseRouter().clearInterpolationCache();

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
            case TUNDRA -> BlockType.DIRT;  // Dirt beneath snowy surface
            case TAIGA -> BlockType.DIRT;  // Forest soil
            case STONY_PEAKS -> BlockType.STONE;  // Solid rock mountains
            case GRAVEL_BEACH -> BlockType.SAND;  // Sandy subsurface beneath gravel
            case ICE_FIELDS -> BlockType.ICE;  // Deep glacial ice layers
            case BADLANDS -> BlockType.RED_SANDSTONE;  // Sedimentary layers beneath badlands

            // Ocean biomes - subsurface materials
            case OCEAN, DEEP_OCEAN -> BlockType.SAND;  // Sand beneath ocean floor
            case FROZEN_OCEAN -> BlockType.GRAVEL;  // Gravel beneath frozen ocean floor
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
            case TUNDRA -> BlockType.SNOWY_DIRT;  // Snow-covered tundra surface
            case TAIGA -> BlockType.SNOWY_DIRT;  // Snow-covered forest floor
            case STONY_PEAKS -> BlockType.STONE;  // Exposed rocky peaks
            case GRAVEL_BEACH -> BlockType.GRAVEL;  // Gravel shoreline (mixed with sand in decorations)
            case ICE_FIELDS -> BlockType.ICE;  // Glacial ice surface
            case BADLANDS -> BlockType.RED_SAND;  // Eroded red sand mesas

            // Ocean biomes - ocean floor materials
            case OCEAN, DEEP_OCEAN -> BlockType.SAND;  // Sandy ocean floor
            case FROZEN_OCEAN -> BlockType.GRAVEL;  // Gravel ocean floor in cold waters
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

        // Wrap feature generation with ScopedValue for JDK 25 optimization
        // This binding is required for DeterministicRandom convenience methods (15-30% faster)
        DeterministicRandom.runWithScopedRandom(() -> {
            populateChunkWithFeaturesImpl(world, chunk, snowLayerManager);
        });
    }

    /**
     * Internal implementation of feature population (wrapped by ScopedValue binding).
     */
    private void populateChunkWithFeaturesImpl(World world, Chunk chunk, SnowLayerManager snowLayerManager) {
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

        // TODO: Implement mob spawning system (animals, hostile mobs, neutral mobs)
        // - Create MobSpawner class to handle chunk-based mob generation
        // - Implement biome-specific animal spawning (cows, sheep, pigs, etc.)
        // - Add hostile mob spawning based on light levels and biome
        // - Consider mob spawn rates and density limits

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
    // They delegate to the appropriate subsystem generators (BiomeManager, TerrainGenerator).

    /**
     * Gets the continentalness value at the specified world position.
     * Continentalness determines whether terrain is ocean, coast, or land.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Continentalness value in range [-1.0, 1.0]
     */
    public float getContinentalnessAt(int x, int z) {
        // Get continentalness from NoiseRouter (works for all generator types)
        return biomeManager.getNoiseRouter().getContinentalness(x, z);
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
     * Erosion determines flat vs mountainous terrain.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Erosion noise value in range [-1.0, 1.0]
     */
    public float getErosionNoiseAt(int x, int z) {
        return biomeManager.getErosion(x, z);
    }

    /**
     * Gets the base terrain height from continentalness only.
     * This is before erosion, PV, and weirdness are applied.
     * Note: This method's behavior depends on the terrain generator type.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Base terrain height (clamped to world bounds)
     */
    public int getBaseHeightBeforeErosion(int x, int z) {
        // Sample parameters at sea level and generate height
        // All generators use multi-noise parameters (continentalness, erosion, PV, weirdness)
        MultiNoiseParameters params = biomeManager.getNoiseRouter().sampleParameters(x, z, SEA_LEVEL);
        return terrainGenerator.generateHeight(x, z, params);
    }

    /**
     * Gets the actual final terrain height as used in world generation.
     * Uses multi-noise parameters to generate height (terrain-independent).
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Final terrain height used in generation
     */
    public int getFinalTerrainHeight(int x, int z) {
        // Sample parameters and generate height using terrain generator
        MultiNoiseParameters params = biomeManager.getNoiseRouter().sampleParameters(x, z, SEA_LEVEL);
        return terrainGenerator.generateHeight(x, z, params);
    }

    /**
     * Gets the peaks & valleys noise value at the specified world position.
     * This parameter amplifies height extremes for dramatic terrain features.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Peaks & valleys value in range [-1.0, 1.0]
     */
    public float getPeaksValleysAt(int x, int z) {
        return biomeManager.getNoiseRouter().getPeaksValleys(x, z);
    }

    /**
     * Gets the weirdness noise value at the specified world position.
     * This parameter creates plateaus and mesas with terracing effects.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Weirdness value in range [-1.0, 1.0]
     */
    public float getWeirdnessAt(int x, int z) {
        return biomeManager.getNoiseRouter().getWeirdness(x, z);
    }

    /**
     * Gets the terrain generator type used by this world.
     *
     * @return The terrain generator type
     */
    public TerrainGeneratorType getGeneratorType() {
        return terrainGenerator.getType();
    }

    /**
     * Gets comprehensive height calculation debug information at the specified position.
     * Returns generator-specific debug data for F3 visualization.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Height calculation debug info (generator-specific)
     */
    public com.stonebreak.world.generation.debug.HeightCalculationDebugInfo getHeightCalculationDebugInfo(int x, int z) {
        // Sample parameters at sea level
        MultiNoiseParameters params = biomeManager.getNoiseRouter().sampleParameters(x, z, SEA_LEVEL);

        // Delegate to terrain generator to collect debug info
        return terrainGenerator.getHeightCalculationDebugInfo(x, z, params);
    }
}