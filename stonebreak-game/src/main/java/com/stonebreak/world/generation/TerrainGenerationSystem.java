package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.biomes.BiomeManager;
import com.stonebreak.world.generation.biomes.BiomeTerrainModifierRegistry;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.caves.AquiferGenerator;
import com.stonebreak.world.generation.caves.CaveNoiseGenerator;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.features.OreGenerator;
import com.stonebreak.world.generation.features.SurfaceDecorationGenerator;
import com.stonebreak.world.generation.features.VegetationGenerator;
import com.stonebreak.world.generation.terrain.TerrainFeatureRegistry;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
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
    private final AquiferGenerator aquiferGenerator;  // Underground water/lava pools
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
    private final Object animalRandomLock = new Object();

    /**
     * Creates a new terrain generation system with the given seed.
     * Initializes all subsystem generators with default configuration.
     * Uses null progress reporter (no progress updates).
     * Defaults to LEGACY generator for backwards compatibility.
     *
     * @param seed World seed for deterministic generation
     */
    public TerrainGenerationSystem(long seed) {
        this(seed, TerrainGenerationConfig.defaultConfig(), "LEGACY", LoadingProgressReporter.NULL);
    }

    /**
     * Creates a new terrain generation system with the given seed and configuration.
     * Initializes all subsystem generators with the provided configuration.
     * Uses null progress reporter (no progress updates).
     * Defaults to LEGACY generator for backwards compatibility.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public TerrainGenerationSystem(long seed, TerrainGenerationConfig config) {
        this(seed, config, "LEGACY", LoadingProgressReporter.NULL);
    }

    /**
     * Creates a new terrain generation system with the given seed, configuration, generator type, and progress reporter.
     * Initializes all subsystem generators with the provided configuration.
     *
     * Multi-Noise System: Terrain and biomes generate independently using shared parameters.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     * @param generatorType Terrain generator type (e.g., "LEGACY", "SPLINE")
     * @param progressReporter Progress reporter for loading screen updates
     */
    public TerrainGenerationSystem(long seed, TerrainGenerationConfig config, String generatorType, LoadingProgressReporter progressReporter) {
        this.seed = seed;
        this.animalRandom = new Random(); // Use current time for truly random animal spawning
        this.deterministicRandom = new DeterministicRandom(seed);
        this.progressReporter = progressReporter;

        // Initialize specialized generators with injected configuration
        // Multi-Noise System: TerrainGenerator and BiomeManager use shared NoiseRouter (via BiomeManager)
        // PHASE 2: Enable 3D density for SPLINE generator (caves, overhangs, arches, floating islands)
        // LEGACY generator remains 2D for backwards compatibility
        this.terrainGenerator = TerrainGeneratorFactory.createFromString(generatorType, seed, config, true);
        this.biomeManager = new BiomeManager(seed, config);
        this.modifierRegistry = new BiomeTerrainModifierRegistry(seed);  // Phase 2: Initialize modifier registry
        this.caveGenerator = new CaveNoiseGenerator(seed);  // Ridged noise cave system (cheese + spaghetti)
        this.aquiferGenerator = new AquiferGenerator(seed);  // Underground water/lava pools
        this.oreGenerator = new OreGenerator(seed);
        this.vegetationGenerator = new VegetationGenerator(seed);
        this.decorationGenerator = new SurfaceDecorationGenerator(seed);

        // Initialize terrain feature registry with default features (overhangs, arches)
        // Note: CaveSystemFeature is now replaced by integrated cave density
        this.terrainFeatureRegistry = TerrainFeatureRegistry.withDefaults(seed);
    }

    /**
     * Creates a new terrain generation system with the given seed, configuration, and progress reporter.
     * Initializes all subsystem generators with the provided configuration.
     * Defaults to LEGACY generator for backwards compatibility.
     *
     * Multi-Noise System: Terrain and biomes generate independently using shared parameters.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     * @param progressReporter Progress reporter for loading screen updates
     */
    public TerrainGenerationSystem(long seed, TerrainGenerationConfig config, LoadingProgressReporter progressReporter) {
        this(seed, config, "LEGACY", progressReporter);
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
     * Gets the terrain generator for height queries.
     * Consumers can directly query terrain height.
     *
     * @return The terrain generator
     */
    public TerrainGenerator getTerrainGenerator() {
        return terrainGenerator;
    }

    /**
     * Gets the height map generator for height queries (legacy compatibility).
     * This method is deprecated and will only work if using the LEGACY generator.
     *
     * @return The height map generator (only available with LEGACY generator)
     * @deprecated Use {@link #getTerrainGenerator()} instead
     * @throws UnsupportedOperationException if not using LEGACY generator
     */
    @Deprecated
    public HeightMapGenerator getHeightMapGenerator() {
        if (terrainGenerator instanceof com.stonebreak.world.generation.legacy.LegacyTerrainGenerator legacy) {
            return legacy.getHeightMapGenerator();
        }
        throw new UnsupportedOperationException("HeightMapGenerator is only available with LEGACY terrain generator");
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

        // Initialize surface height cache (16x16 array)
        // Caches highest non-air block Y coordinate for each X,Z column
        // Eliminates ~131,000 redundant block accesses during feature generation
        int[][] surfaceHeightCache = new int[WorldConfiguration.CHUNK_SIZE][WorldConfiguration.CHUNK_SIZE];

        // Generate terrain using multi-noise system
        for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
            for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                // Calculate absolute world coordinates
                int worldX = chunkX * WorldConfiguration.CHUNK_SIZE + x;
                int worldZ = chunkZ * WorldConfiguration.CHUNK_SIZE + z;

                // Update progress indicators
                if (x == 0 && z == 0) {
                    updateLoadingProgress("Sampling Noise Parameters");
                }

                // STEP 1: Sample all 6 parameters at this position using interpolation for performance
                // Interpolation reduces noise sampling by 94% (grid sampling + bilinear interpolation)
                MultiNoiseParameters params = biomeManager.getNoiseRouter().sampleInterpolatedParameters(worldX, worldZ, SEA_LEVEL);

                // STEP 2: PASS 1 - Generate base terrain height
                // LEGACY: Uses terrain hints (mesa, peaks, hills, plains)
                // SPLINE: Uses unified multi-parameter spline interpolation
                // Terrain generation happens BEFORE biome selection
                int baseHeight = terrainGenerator.generateHeight(worldX, worldZ, params);

                // STEP 3: Select biome using parameters
                // Reuse params since temperature is noise-based only (no altitude adjustment needed)
                BiomeType biome = biomeManager.getBiomeAtHeight(worldX, worldZ, baseHeight);

                // STEP 4: PASS 2 - Apply biome-specific modifiers
                // Modifiers add fine-tuned features AFTER biome selection:
                // - Badlands: Canyon carving, hoodoo/spire generation
                // - Stony Peaks: Vertical amplification, rocky outcrops
                // - Desert: Rolling dune patterns
                int height = modifierRegistry.applyModifier(biome, baseHeight, params, worldX, worldZ);

                // Update progress mid-chunk
                if (x == 8 && z == 8) {
                    updateLoadingProgress("Applying Surface Materials");
                }

                // STEP 5: Generate blocks for this column
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    // Check if below height (default solid)
                    boolean shouldBeSolid = y < height;
                    boolean isCave = false;  // Track if this is a cave for aquifer application

                    // Check cave density if potentially underground
                    // Caves are integrated into terrain generation (Minecraft 1.18+ approach)
                    if (shouldBeSolid && caveGenerator.canGenerateCaves(y)) {
                        float caveDensity = caveGenerator.sampleCaveDensity(worldX, y, worldZ, height);
                        // High cave density carves out the block
                        if (caveDensity > 0.0f) {
                            shouldBeSolid = false; // Cave removes this block
                            isCave = true;  // Mark as cave for aquifer
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
                }
            }
        }

        // STEP 6: Rebuild surface height cache to reflect actual post-cave/feature surface
        // This ensures spawn calculation and feature placement use the TRUE surface, not pre-cave terrain
        updateLoadingProgress("Building Surface Cache");
        System.out.println("[SURFACE-CACHE] Starting surface cache rebuild for chunk (" + chunkX + ", " + chunkZ + ")");
        for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
            for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                int actualSurface = -1;

                // Scan from top down to find first solid, non-water block
                for (int y = WORLD_HEIGHT - 1; y >= 0; y--) {
                    BlockType block = chunk.getBlock(x, y, z);
                    if (block != null && block != BlockType.AIR && block != BlockType.WATER) {
                        actualSurface = y;
                        break;
                    }
                }

                // Store actual surface (or -1 if column is all air/water)
                surfaceHeightCache[x][z] = actualSurface;
            }
        }

        System.out.println("[SURFACE-CACHE] Finished surface cache rebuild for chunk (" + chunkX + ", " + chunkZ + ")");

        // Add debug logging for spawn chunk (0, 0) to verify surface detection
        if (chunkX == 0 && chunkZ == 0) {
            System.out.println("[SPAWN] Spawn chunk surface at (0,0): Y=" + surfaceHeightCache[0][0]);
        }

        // Set the surface height cache on the chunk
        // This cache will be used by VegetationGenerator and SurfaceDecorationGenerator
        // to avoid ~131,000 redundant block accesses per chunk
        chunk.setSurfaceHeightCache(surfaceHeightCache);

        // Flood-fill ocean water from surface
        // This ensures caves stay dry while ocean water fills properly
        updateLoadingProgress("Filling Ocean Water");
        floodFillOceanWater(chunk);

        // Features will be populated after chunk registration to avoid recursion
        chunk.setFeaturesPopulated(false);

        // Clear parameter interpolation cache to prevent memory accumulation
        // Cache is typically small (~5-10 entries per chunk) but should be cleared regularly
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
     * Flood-fills ocean water from the surface down through connected air blocks.
     *
     * This algorithm ensures that:
     * - Ocean water fills from sea level down to ocean floor
     * - Caves and enclosed air pockets stay dry (not connected to surface)
     * - Coastal caves that open to ocean will fill with water (realistic)
     *
     * Algorithm:
     * 1. Identify ocean entry points (air at sea level with terrain below)
     * 2. BFS flood-fill from entry points through connected air blocks
     * 3. Fill all reachable air blocks at or below sea level with water
     *
     * @param chunk The chunk to fill with ocean water
     */
    private void floodFillOceanWater(Chunk chunk) {
        int chunkSize = WorldConfiguration.CHUNK_SIZE;
        boolean[][][] visited = new boolean[chunkSize][SEA_LEVEL + 1][chunkSize];
        Queue<int[]> queue = new ArrayDeque<>(256);

        // Get chunk world coordinates for continentalness lookup
        int chunkWorldX = chunk.getChunkX() * chunkSize;
        int chunkWorldZ = chunk.getChunkZ() * chunkSize;

        // Find all ocean entry points (air at sea level with low continentalness)
        for (int x = 0; x < chunkSize; x++) {
            for (int z = 0; z < chunkSize; z++) {
                // Check if this column has air at sea level
                if (chunk.getBlock(x, SEA_LEVEL, z) == BlockType.AIR) {
                    // Calculate world coordinates for continentalness check
                    int worldX = chunkWorldX + x;
                    int worldZ = chunkWorldZ + z;

                    // Check continentalness to verify this is actually ocean, not a floating island
                    float continentalness = biomeManager.getNoiseRouter().getContinentalness(worldX, worldZ);
                    if (continentalness >= -0.45f) {
                        // Not ocean - skip this position (prevents flooding under floating islands)
                        continue;
                    }

                    // Check if there's solid terrain below (not air all the way down)
                    boolean hasTerrain = false;
                    for (int y = SEA_LEVEL - 1; y >= 0; y--) {
                        BlockType block = chunk.getBlock(x, y, z);
                        if (block != BlockType.AIR) {
                            hasTerrain = true;
                            break;
                        }
                    }
                    if (hasTerrain) {
                        // This is an ocean entry point - start flood-fill from here
                        queue.add(new int[]{x, SEA_LEVEL, z});
                        visited[x][SEA_LEVEL][z] = true;
                    }
                }
            }
        }

        // BFS flood-fill through connected air blocks
        // Direction offsets: +X, -X, +Y, -Y, +Z, -Z
        int[] dx = {1, -1, 0, 0, 0, 0};
        int[] dy = {0, 0, 1, -1, 0, 0};
        int[] dz = {0, 0, 0, 0, 1, -1};

        while (!queue.isEmpty()) {
            int[] pos = queue.poll();
            int x = pos[0], y = pos[1], z = pos[2];

            // Fill this block with water (if it's air and at or below sea level)
            if (y <= SEA_LEVEL && chunk.getBlock(x, y, z) == BlockType.AIR) {
                chunk.setBlock(x, y, z, BlockType.WATER);
            }

            // Check all 6 neighbors
            for (int i = 0; i < 6; i++) {
                int nx = x + dx[i];
                int ny = y + dy[i];
                int nz = z + dz[i];

                // Bounds check (stay within chunk, don't go above sea level or below 0)
                if (nx < 0 || nx >= chunkSize ||
                    ny < 0 || ny > SEA_LEVEL ||
                    nz < 0 || nz >= chunkSize) {
                    continue;
                }

                // Skip if already visited
                if (visited[nx][ny][nz]) {
                    continue;
                }

                // Only spread through air blocks
                if (chunk.getBlock(nx, ny, nz) == BlockType.AIR) {
                    visited[nx][ny][nz] = true;
                    queue.add(new int[]{nx, ny, nz});
                }
            }
        }
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
        // For LEGACY generator, this will use just continentalness (hint will be NORMAL)
        // For SPLINE generator, this will use all parameters
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