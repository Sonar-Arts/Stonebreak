package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.util.SplineInterpolator;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.generation.mobs.MobGenerator;
import com.stonebreak.world.DeterministicRandom;

import java.util.Random;

/**
 * Handles all terrain generation including height calculation, biome determination,
 * and noise-based world generation.
 */
public class TerrainGenerationSystem {
    public static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    public static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    
    private final long seed;
    private final Random random;
    private final Random animalRandom;
    private final DeterministicRandom deterministicRandom;
    private final NoiseGenerator terrainNoise;
    private final NoiseGenerator temperatureNoise;
    private final NoiseGenerator continentalnessNoise;
    private final SplineInterpolator terrainSpline;
    private final Object randomLock = new Object();
    private final Object animalRandomLock = new Object();
    private final Object treeRandomLock = new Object();
    
    public TerrainGenerationSystem(long seed) {
        this.seed = seed;
        this.random = new Random(seed); // Deterministic random for terrain features
        this.animalRandom = new Random(); // Use current time for truly random animal spawning
        this.deterministicRandom = new DeterministicRandom(seed);
        this.terrainNoise = new NoiseGenerator(seed);
        this.temperatureNoise = new NoiseGenerator(seed + 1);
        this.continentalnessNoise = new NoiseGenerator(seed + 2);
        this.terrainSpline = new SplineInterpolator();

        initializeTerrainSpline();
    }
    
    /**
     * Initializes the terrain spline with height control points.
     */
    private void initializeTerrainSpline() {
        terrainSpline.addPoint(-1.0, 70);  // Islands (changed from 20 to simulate islands above sea level)
        terrainSpline.addPoint(-0.8, 20);  // Deep ocean (new point for preserved deep ocean areas)
        terrainSpline.addPoint(-0.4, 60);  // Approaching coast
        terrainSpline.addPoint(-0.2, 70);  // Just above sea level
        terrainSpline.addPoint(0.1, 75);   // Lowlands
        terrainSpline.addPoint(0.3, 120);  // Mountain foothills
        terrainSpline.addPoint(0.7, 140);  // Common foothills (new point for enhanced foothill generation)
        terrainSpline.addPoint(1.0, 200);  // High peaks
    }
    
    /**
     * Generates terrain height for the specified world position.
     */
    public int generateTerrainHeight(int x, int z) {
        float continentalness = continentalnessNoise.noise(x / 800.0f, z / 800.0f);
        int height = (int) terrainSpline.interpolate(continentalness);
        return Math.max(1, Math.min(height, WORLD_HEIGHT - 1));
    }
    
    /**
     * Gets the continentalness value at the specified world position.
     */
    public float getContinentalnessAt(int x, int z) {
        return continentalnessNoise.noise(x / 800.0f, z / 800.0f);
    }
    
    /**
     * Generates moisture value for determining biomes.
     */
    public float generateMoisture(int x, int z) {
        float nx = x / 200.0f;
        float nz = z / 200.0f;
        
        return terrainNoise.noise(nx + 100, nz + 100) * 0.5f + 0.5f; // Range 0.0 to 1.0
    }
    
    /**
     * Generates temperature value for determining biomes.
     */
    public float generateTemperature(int x, int z) {
        float nx = x / 300.0f; // Different scale for temperature
        float nz = z / 300.0f;
        return temperatureNoise.noise(nx - 50, nz - 50) * 0.5f + 0.5f; // Range 0.0 to 1.0
    }
    
    /**
     * Determines the biome type based on temperature and moisture values.
     */
    public BiomeType getBiomeType(int x, int z) {
        float moisture = generateMoisture(x, z);
        float temperature = generateTemperature(x, z);

        if (temperature > 0.65f) { // Hot
            if (moisture < 0.35f) {
                return BiomeType.DESERT;
            } else {
                return BiomeType.RED_SAND_DESERT; // Hot and somewhat moist/varied = Red Sand Desert
            }
        } else if (temperature < 0.35f) { // Cold
            if (moisture > 0.6f) {
                return BiomeType.SNOWY_PLAINS; // Cold and moist = snowy plains
            } else {
                return BiomeType.PLAINS; // Cold but dry = regular plains
            }
        } else { // Temperate
            if (moisture < 0.3f) {
                return BiomeType.DESERT; // Temperate but dry = also desert like
            } else {
                return BiomeType.PLAINS;
            }
        }
    }

    
    /**
     * Gets the world seed.
     */
    public long getSeed() {
        return seed;
    }

    /**
     * Gets the random instance for animal spawning (truly random, not deterministic).
     */
    public Random getAnimalRandom() {
        return animalRandom;
    }

    /**
     * Gets the random lock for animal spawning synchronization.
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
     * Generates only the bare terrain for a new chunk (no features like ores or trees).
     * Uses chunk.setBlock() which internally uses CCO API for block operations.
     * Generates mesh using MMS API after terrain generation is complete.
     */
    public Chunk generateBareChunk(int chunkX, int chunkZ) {
        updateLoadingProgress("Generating Base Terrain Shape");
        Chunk chunk = new Chunk(chunkX, chunkZ);

        // Generate terrain - chunk.setBlock() uses CCO BlockWriter internally
        for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
            for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                // Calculate absolute world coordinates
                int worldX = chunkX * WorldConfiguration.CHUNK_SIZE + x;
                int worldZ = chunkZ * WorldConfiguration.CHUNK_SIZE + z;

                // Generate height map using noise
                int height = generateTerrainHeight(worldX, worldZ);

                // Update progress for biome determination
                if (x == 0 && z == 0) {
                    updateLoadingProgress("Determining Biomes");
                }
                BiomeType biome = getBiomeType(worldX, worldZ);

                // Generate blocks based on height and biome
                if (x == 8 && z == 8) { // Update progress mid-chunk
                    updateLoadingProgress("Applying Biome Materials");
                }
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    BlockType blockType;

                    if (y == 0) {
                        blockType = BlockType.BEDROCK;
                    } else if (y < height - 4) {
                        // Deeper layers - biome can influence stone type
                        if (biome == BiomeType.RED_SAND_DESERT && y < height - 10 && deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "magma", 0.6f)) {
                            blockType = BlockType.MAGMA; // More magma deeper in volcanic areas
                        } else {
                            blockType = BlockType.STONE;
                        }
                    } else if (y < height - 1) {
                        // Sub-surface layer
                        blockType = (biome != null) ? switch (biome) {
                            case RED_SAND_DESERT -> BlockType.RED_SANDSTONE;
                            case DESERT -> BlockType.SANDSTONE;
                            case PLAINS -> BlockType.DIRT;
                            case SNOWY_PLAINS -> BlockType.DIRT;
                            default -> BlockType.DIRT;
                        } : BlockType.DIRT;
                    } else if (y < height) {
                        // Top layer
                        blockType = (biome != null) ? switch (biome) {
                            case DESERT -> BlockType.SAND;
                            case RED_SAND_DESERT -> BlockType.RED_SAND;
                            case PLAINS -> BlockType.GRASS;
                            case SNOWY_PLAINS -> BlockType.SNOWY_DIRT;
                            default -> BlockType.DIRT; // Default case to handle any new biome types
                        } : BlockType.DIRT;
                    } else if (y < SEA_LEVEL) { // Water level
                        // No water in volcanic biomes above a certain height
                        if (biome == BiomeType.RED_SAND_DESERT && height > SEA_LEVEL) {
                             blockType = BlockType.AIR;
                        } else {
                            blockType = BlockType.WATER;
                        }
                    } else {
                        blockType = BlockType.AIR;
                    }
                    chunk.setBlock(x, y, z, blockType); // Uses CCO BlockWriter internally
                }
                // Trees and other features will be generated in populateChunkWithFeatures
            }
        }

        // Mesh generation happens automatically via CCO dirty tracking when chunk is rendered
        chunk.setFeaturesPopulated(false); // Explicitly mark as not populated
        return chunk;
    }

    /**
     * Populates an existing chunk with features like ores and trees.
     * Uses world.setBlockAt() which internally uses CCO API for global block placement.
     * Mesh regeneration happens automatically via CCO dirty tracking.
     */
    public void populateChunkWithFeatures(World world, Chunk chunk, SnowLayerManager snowLayerManager) {
        if (chunk == null || chunk.areFeaturesPopulated()) {
            return; // Already populated or null chunk
        }

        updateLoadingProgress("Adding Surface Decorations & Details");

        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();

        for (int x = 0; x < WorldConfiguration.CHUNK_SIZE; x++) {
            for (int z = 0; z < WorldConfiguration.CHUNK_SIZE; z++) {
                int worldX = chunkX * WorldConfiguration.CHUNK_SIZE + x;
                int worldZ = chunkZ * WorldConfiguration.CHUNK_SIZE + z;

                // Determine surface height for this column *within this chunk*
                // This height is relative to the chunk's own blocks, not a fresh noise query
                int surfaceHeight = 0;
                for (int yScan = WORLD_HEIGHT - 1; yScan >= 0; yScan--) {
                    if (chunk.getBlock(x, yScan, z) != BlockType.AIR) {
                        surfaceHeight = yScan + 1; // surfaceHeight is the first AIR block *above* solid ground, or top of solid ground
                        break;
                    }
                }
                 if (surfaceHeight == 0 && chunk.getBlock(x,0,z) != BlockType.AIR) { // Edge case: column is solid to y=0
                    surfaceHeight = 1; // Place features starting at y=1 if ground is at y=0
                }
                
                BiomeType biome = getBiomeType(worldX, worldZ);

                // Generate Ores & Biome Specific Features
                for (int y = 1; y < surfaceHeight -1; y++) { // Iterate up to just below surface
                    BlockType currentBlock = chunk.getBlock(x, y, z);
                    BlockType oreType = null;

                    if (currentBlock == BlockType.STONE) {
                        if (deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "coal_ore", 0.015f)) { // Increased coal slightly
                            oreType = BlockType.COAL_ORE;
                        } else if (deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "iron_ore", 0.008f) && y < 50) { // Increased iron slightly, wider range
                            oreType = BlockType.IRON_ORE;
                        }
                    } else if (biome == BiomeType.RED_SAND_DESERT && (currentBlock == BlockType.RED_SAND || currentBlock == BlockType.STONE || currentBlock == BlockType.MAGMA) && y > 20 && y < surfaceHeight - 5) {
                        // Crystal generation in Volcanic biomes, embedded in Obsidian, Stone or Magma
                        if (deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "crystal", 0.02f)) { // Chance for Crystal
                            oreType = BlockType.CRYSTAL;
                        }
                    }

                    if (oreType != null) {
                        world.setBlockAt(worldX, y, worldZ, oreType);
                    }
                }
                
                // Generate Trees (in PLAINS and SNOWY_PLAINS biomes)
                if (biome == BiomeType.PLAINS) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.GRASS) { // Ensure tree spawns on grass
                        if (deterministicRandom.shouldGenerate(worldX, worldZ, "tree", 0.01f) && surfaceHeight > 64) { // 1% total tree chance
                            // Determine tree type: 60% regular oak, 40% elm trees
                            boolean shouldGenerateElm = deterministicRandom.shouldGenerate(worldX, worldZ, "tree_type", 0.4f); // 40% chance for elm

                            if (shouldGenerateElm) {
                                TreeGenerator.generateElmTree(world, chunk, x, surfaceHeight, z, deterministicRandom.getRandomForPosition(worldX, worldZ, "elm_tree"), treeRandomLock);
                            } else {
                                TreeGenerator.generateTree(world, chunk, x, surfaceHeight, z);
                            }
                        }
                    }
                } else if (biome == BiomeType.SNOWY_PLAINS) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SNOWY_DIRT) { // Pine trees spawn on snowy dirt
                        if (deterministicRandom.shouldGenerate(worldX, worldZ, "pine_tree", 0.015f) && surfaceHeight > 64) { // Slightly higher chance for pine trees
                            TreeGenerator.generatePineTree(world, chunk, x, surfaceHeight, z);
                        }
                    }
                }
                
                // Generate Flowers on grass surfaces in PLAINS biome
                if (biome == BiomeType.PLAINS && surfaceHeight > 64 && surfaceHeight < WORLD_HEIGHT) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.GRASS &&
                        chunk.getBlock(x, surfaceHeight, z) == BlockType.AIR) {
                        if (deterministicRandom.shouldGenerate(worldX, worldZ, "flower", 0.08f)) { // 8% chance for flowers
                            BlockType flowerType = deterministicRandom.getBoolean(worldX, worldZ, "flower_type") ? BlockType.ROSE : BlockType.DANDELION;
                            world.setBlockAt(worldX, surfaceHeight, worldZ, flowerType);
                        }
                    }
                }
                
                // Generate Gravel patches on sand surfaces in DESERT biome
                if (biome == BiomeType.DESERT && surfaceHeight > 64 && surfaceHeight < WORLD_HEIGHT) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SAND) {
                        if (deterministicRandom.shouldGenerate(worldX, worldZ, "gravel_patch", 0.01f)) { // 1% chance for gravel patches
                            // Create small gravel patches (2x2 or 3x3)
                            int patchSize = deterministicRandom.getBoolean(worldX, worldZ, "gravel_patch_size") ? 2 : 3; // Random patch size

                            for (int dx = 0; dx < patchSize; dx++) {
                                for (int dz = 0; dz < patchSize; dz++) {
                                    int patchWorldX = worldX + dx - patchSize/2;
                                    int patchWorldZ = worldZ + dz - patchSize/2;
                                    int patchY = generateTerrainHeight(patchWorldX, patchWorldZ) - 1;

                                    // Only place gravel if the spot is sand
                                    if (world.getBlockAt(patchWorldX, patchY, patchWorldZ) == BlockType.SAND) {
                                        world.setBlockAt(patchWorldX, patchY, patchWorldZ, BlockType.GRAVEL);
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Generate Ice patches and Snow layers in SNOWY_PLAINS biome
                if (biome == BiomeType.SNOWY_PLAINS && surfaceHeight > 64 && surfaceHeight < WORLD_HEIGHT) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SNOWY_DIRT &&
                        chunk.getBlock(x, surfaceHeight, z) == BlockType.AIR) {
                        float featureChance = deterministicRandom.getFloat(worldX, worldZ, "snow_ice_feature");

                        if (featureChance < 0.03) { // 3% chance for ice patches
                            world.setBlockAt(worldX, surfaceHeight, worldZ, BlockType.ICE);
                        } else if (featureChance < 0.08) { // Additional 5% chance for snow layers
                            world.setBlockAt(worldX, surfaceHeight, worldZ, BlockType.SNOW);
                            // Set initial snow layer count (1-3 layers randomly)
                            int layers = 1 + deterministicRandom.getInt(worldX, worldZ, "snow_layers", 3); // 1, 2, or 3 layers
                            snowLayerManager.setSnowLayers(worldX, surfaceHeight, worldZ, layers);
                        }
                    }
                }

                // Generate Clay patches in RED_SAND_DESERT biome
                if (biome == BiomeType.RED_SAND_DESERT && surfaceHeight > 64 && surfaceHeight < WORLD_HEIGHT) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.RED_SAND) {
                        float clayChance;
                        synchronized (randomLock) {
                            clayChance = random.nextFloat();
                        }

                        if (clayChance < 0.008) { // 0.8% chance for clay patches (much rarer)
                            // Create organic-looking clay patches
                            int centerX = worldX;
                            int centerZ = worldZ;
                            int radius;
                            synchronized (randomLock) {
                                radius = 1 + random.nextInt(2); // Radius 1-2 blocks
                            }

                            // Generate circular/organic patch with some randomness
                            for (int dx = -radius; dx <= radius; dx++) {
                                for (int dz = -radius; dz <= radius; dz++) {
                                    int patchWorldX = centerX + dx;
                                    int patchWorldZ = centerZ + dz;

                                    // Calculate distance from center
                                    double distance = Math.sqrt(dx * dx + dz * dz);

                                    // Only place clay within radius with some randomness for organic shape
                                    if (distance <= radius) {
                                        float placeChance;
                                        synchronized (randomLock) {
                                            // Higher chance near center, lower chance at edges
                                            placeChance = 1.0f - (float)(distance / radius) * 0.4f;
                                            if (random.nextFloat() < placeChance) {
                                                int patchY = generateTerrainHeight(patchWorldX, patchWorldZ) - 1;

                                                // Only place clay if the spot is red sand
                                                if (world.getBlockAt(patchWorldX, patchY, patchWorldZ) == BlockType.RED_SAND) {
                                                    world.setBlockAt(patchWorldX, patchY, patchWorldZ, BlockType.CLAY);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // No trees in DESERT or VOLCANIC biomes by default
            }
        }
        
        // Process mob spawning for this chunk
        BiomeType chunkBiome = getBiomeType(chunkX * WorldConfiguration.CHUNK_SIZE + WorldConfiguration.CHUNK_SIZE / 2, chunkZ * WorldConfiguration.CHUNK_SIZE + WorldConfiguration.CHUNK_SIZE / 2);
        MobGenerator.processChunkMobSpawning(world, chunk, chunkBiome, animalRandom, animalRandomLock);

        // Mesh regeneration happens automatically via CCO dirty tracking when chunk is rendered
        chunk.setFeaturesPopulated(true);
    }
}