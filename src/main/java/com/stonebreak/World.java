package com.stonebreak;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap; // Added
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages the game world and chunks.
 */
public class World {
      // World settings
    public static final int CHUNK_SIZE = 16;
    public static final int WORLD_HEIGHT = 256;
    private static final int RENDER_DISTANCE = 8;
    private static final int UNLOAD_DISTANCE = RENDER_DISTANCE + 4; // Keep chunks loaded a bit beyond render distance
      // Seed for terrain generation
    private final long seed;
    private final Random random;
    private final NoiseGenerator terrainNoise;
    private final NoiseGenerator temperatureNoise; // For biome determination
    private final Object randomLock = new Object(); // Lock for synchronizing random access
    
    // Stores all chunks in the world
    private final Map<ChunkPosition, Chunk> chunks;
    
    // Tracks which chunks need mesh updates
    private final ExecutorService chunkBuildExecutor;
    private final Set<Chunk> chunksToBuildMesh; // Chunks needing their mesh data (re)built
    private final Queue<Chunk> chunksReadyForGLUpload; // Chunks with mesh data ready for GL upload
    
    // Chunk unloading timer
    private float chunkUnloadCounter = 0;
    private float chunkRenderTickCounter = 0; // Timer for render tick
    private static final float CHUNK_RENDER_TICK_INTERVAL = 0.5f; // Check every 0.5 seconds

    public World() {
        this.seed = System.currentTimeMillis();
        this.random = new Random(seed);
        this.terrainNoise = new NoiseGenerator(seed);
        this.temperatureNoise = new NoiseGenerator(seed + 1); // Use a different seed for temperature
        this.chunks = new ConcurrentHashMap<>();
        
        // Initialize thread pool for chunk mesh building
        // Use half available processors, minimum 1
        int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        this.chunkBuildExecutor = Executors.newFixedThreadPool(numThreads);
        
        this.chunksToBuildMesh = ConcurrentHashMap.newKeySet(); // Changed to concurrent set
        this.chunksReadyForGLUpload = new ConcurrentLinkedQueue<>();
        
        System.out.println("Creating world with seed: " + seed + ", using " + numThreads + " mesh builder threads.");
    }    public void update() {
        // Process requests to build mesh data (async)
        processChunkMeshBuildRequests();
        // Apply mesh data to GL objects on the main thread
        applyPendingGLUpdates();
        
        // Unload distant chunks (only check every few seconds to avoid constant loading/unloading)
        chunkUnloadCounter += Game.getDeltaTime();
        if (chunkUnloadCounter >= 5.0f) { // Check every 5 seconds
            unloadDistantChunks();
            chunkUnloadCounter = 0;
        }

        // Periodically ensure visible chunks are meshed
        chunkRenderTickCounter += Game.getDeltaTime();
        if (chunkRenderTickCounter >= CHUNK_RENDER_TICK_INTERVAL) {
            ensureVisibleChunksAreMeshed();
            chunkRenderTickCounter = 0;
        }
    }

    /**
     * Iterates through chunks within render distance of the player and ensures
     * they are generated and queued for mesh building if necessary.
     */
    private void ensureVisibleChunksAreMeshed() {
        Player player = Game.getPlayer();
        if (player == null) {
            return; // No player to base visibility on
        }

        int playerChunkX = (int) Math.floor(player.getPosition().x / CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / CHUNK_SIZE);

        for (int cx = playerChunkX - RENDER_DISTANCE; cx <= playerChunkX + RENDER_DISTANCE; cx++) {
            for (int cz = playerChunkZ - RENDER_DISTANCE; cz <= playerChunkZ + RENDER_DISTANCE; cz++) {
                ChunkPosition position = new ChunkPosition(cx, cz);
                Chunk chunk = chunks.get(position);

                if (chunk == null) {
                    chunk = getChunkAt(cx, cz); // This will create a bare chunk if it doesn't exist
                    if (chunk == null) {
                        // Failed to create/get chunk, skip
                        continue;
                    }
                }

                // At this point, 'chunk' exists. Populate if not already populated.
                if (!chunk.areFeaturesPopulated()) {
                    populateChunkWithFeatures(chunk); // This sets featuresPopulated to true internally
                    // Mark for mesh rebuild as population changes blocks
                    synchronized (chunk) {
                        chunk.setDataReadyForGL(false);
                        chunk.setMeshDataGenerationScheduledOrInProgress(false);
                    }
                    conditionallyScheduleMeshBuild(chunk);
                    // Neighbors affected by setBlockAt during population are handled by setBlockAt itself.
                }

                // Now proceed with mesh status check for the (potentially newly populated) chunk
                boolean isPerfectlyFine = chunk.isMeshGenerated() && chunk.isDataReadyForGL();
                
                if (!isPerfectlyFine) {
                    // System.out.println("EnsureVisible: Chunk (" + cx + "," + cz + ") is not perfect. Resetting and scheduling.");
                    synchronized (chunk) {
                        chunk.setDataReadyForGL(false); // Ensure it's reset if not perfect
                        chunk.setMeshDataGenerationScheduledOrInProgress(false);
                    }
                    conditionallyScheduleMeshBuild(chunk);

                    // Also check and schedule direct neighbors if they are not perfect
                    int[][] neighborOffsets = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
                    for (int[] offset : neighborOffsets) {
                        ChunkPosition neighborPos = new ChunkPosition(cx + offset[0], cz + offset[1]);
                        Chunk neighbor = chunks.get(neighborPos); // Get neighbor, don't auto-create here
                        if (neighbor != null) {
                            // If neighbor exists but isn't populated, it will be handled when its turn comes in the outer loop
                            // or by getChunksAroundPlayer if it's an edge chunk.
                            // Only force a re-mesh if it's populated but not perfect.
                            if (neighbor.areFeaturesPopulated()) {
                                boolean neighborIsPerfect = neighbor.isMeshGenerated() && neighbor.isDataReadyForGL();
                                if (!neighborIsPerfect) {
                                    synchronized (neighbor) {
                                        neighbor.setDataReadyForGL(false);
                                        neighbor.setMeshDataGenerationScheduledOrInProgress(false);
                                    }
                                    conditionallyScheduleMeshBuild(neighbor);
                                }
                            }
                        }
                    }
                } else {
                    // Chunk is perfectly fine, but still call conditionallyScheduleMeshBuild
                    // to catch rare cases where it might have been dropped from queues.
                    conditionallyScheduleMeshBuild(chunk);
                }
            }
        }
    }
    
    /**
     * Gets the chunk at the specified position.
     * If the chunk doesn't exist, it will be generated.
     */
    public Chunk getChunkAt(int x, int z) {
        ChunkPosition position = new ChunkPosition(x, z);
        Chunk chunk = chunks.get(position);
        
        if (chunk == null) {
            // Use the new safe method for generation, registration, and queueing
            chunk = safelyGenerateAndRegisterChunk(x, z);
        }
        
        return chunk;
    }
    
    /**
     * Checks if a chunk exists at the specified position.
     */
    public boolean hasChunkAt(int x, int z) {
        return chunks.containsKey(new ChunkPosition(x, z));
    }
    
    /**
     * Gets the block type at the specified world position.
     */
    public BlockType getBlockAt(int x, int y, int z) {
        if (y < 0 || y >= WORLD_HEIGHT) {
            return BlockType.AIR;
        }
        
        int chunkX = Math.floorDiv(x, CHUNK_SIZE);
        int chunkZ = Math.floorDiv(z, CHUNK_SIZE);
        
        Chunk chunk = getChunkAt(chunkX, chunkZ);
        
        // If the chunk failed to generate (e.g., safelyGenerateAndRegisterChunk returned null),
        // treat its blocks as AIR to prevent NullPointerExceptions.
        if (chunk == null) {
            return BlockType.AIR;
        }
        
        int localX = Math.floorMod(x, CHUNK_SIZE);
        int localZ = Math.floorMod(z, CHUNK_SIZE);
        
        return chunk.getBlock(localX, y, localZ);
    }
    
    /**
     * Sets the block type at the specified world position.
     * @return true if the block was successfully set, false otherwise (e.g., out of bounds).
     */
    public boolean setBlockAt(int x, int y, int z, BlockType blockType) {
        if (y < 0 || y >= WORLD_HEIGHT) {
            return false;
        }
        
        int chunkX = Math.floorDiv(x, CHUNK_SIZE);
        int chunkZ = Math.floorDiv(z, CHUNK_SIZE);
        
        Chunk chunk = getChunkAt(chunkX, chunkZ);
        
        int localX = Math.floorMod(x, CHUNK_SIZE);
        int localZ = Math.floorMod(z, CHUNK_SIZE);
        
        chunk.setBlock(localX, y, localZ, blockType);

        // Mark chunk for mesh data rebuild. The existing mesh (if any) will continue to be used until the new one is ready.
        synchronized (chunk) {
            // chunk.setMeshGenerated(false); // Keep existing mesh active for rendering
            chunk.setDataReadyForGL(false); // Indicate that current GL data (if any) is stale / new data is needed
            chunk.setMeshDataGenerationScheduledOrInProgress(false); // Allow re-queue for mesh data generation
        }
        conditionallyScheduleMeshBuild(chunk);
        
        // Also mark neighbors for rebuild if the block was on an edge.
        Chunk neighbor;
        if (localX == 0) {
            neighbor = chunks.get(new ChunkPosition(chunkX - 1, chunkZ));
            if (neighbor != null) {
                synchronized (neighbor) {
                    // neighbor.setMeshGenerated(false);
                    neighbor.setDataReadyForGL(false);
                    neighbor.setMeshDataGenerationScheduledOrInProgress(false);
                }
                conditionallyScheduleMeshBuild(neighbor);
            }
        }
        if (localX == CHUNK_SIZE - 1) {
            neighbor = chunks.get(new ChunkPosition(chunkX + 1, chunkZ));
            if (neighbor != null) {
                synchronized (neighbor) {
                    // neighbor.setMeshGenerated(false);
                    neighbor.setDataReadyForGL(false);
                    neighbor.setMeshDataGenerationScheduledOrInProgress(false);
                }
                conditionallyScheduleMeshBuild(neighbor);
            }
        }
        if (localZ == 0) {
            neighbor = chunks.get(new ChunkPosition(chunkX, chunkZ - 1));
            if (neighbor != null) {
                synchronized (neighbor) {
                    // neighbor.setMeshGenerated(false);
                    neighbor.setDataReadyForGL(false);
                    neighbor.setMeshDataGenerationScheduledOrInProgress(false);
                }
                conditionallyScheduleMeshBuild(neighbor);
            }
        }
        if (localZ == CHUNK_SIZE - 1) {
            neighbor = chunks.get(new ChunkPosition(chunkX, chunkZ + 1));
            if (neighbor != null) {
                synchronized (neighbor) {
                    // neighbor.setMeshGenerated(false);
                    neighbor.setDataReadyForGL(false);
                    neighbor.setMeshDataGenerationScheduledOrInProgress(false);
                }
                conditionallyScheduleMeshBuild(neighbor);
           }
       }
       return true;
   }
   
   /**
     * Generates only the bare terrain for a new chunk (no features like ores or trees).
     * Uses chunk.setBlock() for local block placement.
     */
    private Chunk generateBareChunk(int chunkX, int chunkZ) {
        Chunk chunk = new Chunk(chunkX, chunkZ);
        
        // Generate terrain
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                // Calculate absolute world coordinates
                int worldX = chunkX * CHUNK_SIZE + x;
                int worldZ = chunkZ * CHUNK_SIZE + z;
                
                // Generate height map using noise
                int height = generateTerrainHeight(worldX, worldZ);
                BiomeType biome = getBiomeType(worldX, worldZ);
                
                // Generate blocks based on height and biome
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    BlockType blockType;
                    
                    if (y == 0) {
                        blockType = BlockType.BEDROCK;
                    } else if (y < height - 4) {
                        // Deeper layers - biome can influence stone type
                        if (biome == BiomeType.RED_SAND_DESERT && y < height - 10 && random.nextFloat() < 0.6f) {
                            blockType = BlockType.MAGMA; // More magma deeper in volcanic areas
                        } else {
                            blockType = BlockType.STONE;
                        }                    } else if (y < height - 1) {
                        // Sub-surface layer
                        blockType = (biome != null) ? switch (biome) {
                            case RED_SAND_DESERT -> BlockType.RED_SANDSTONE;
                            case DESERT -> BlockType.SANDSTONE;
                            case PLAINS -> BlockType.DIRT;
                            default -> BlockType.DIRT;
                        } : BlockType.DIRT;                    } else if (y < height) {
                        // Top layer
                        blockType = (biome != null) ? switch (biome) {
                            case DESERT -> BlockType.SAND;
                            case RED_SAND_DESERT -> BlockType.RED_SAND;
                            case PLAINS -> BlockType.GRASS;
                            default -> BlockType.DIRT; // Default case to handle any new biome types
                        } : BlockType.DIRT;
                    }else if (y < 64) { // Water level
                        // No water in volcanic biomes above a certain height
                        if (biome == BiomeType.RED_SAND_DESERT && height > 64) {
                             blockType = BlockType.AIR;
                        } else {
                            blockType = BlockType.WATER;
                        }
                    } else {
                        blockType = BlockType.AIR;
                    }
                    chunk.setBlock(x, y, z, blockType); // Local placement
                }
                // Trees and other features will be generated in populateChunkWithFeatures
            }
        }
        chunk.setFeaturesPopulated(false); // Explicitly mark as not populated
        return chunk;
    }

    /**
     * Populates an existing chunk with features like ores and trees.
     * Uses this.setBlockAt() for global block placement.
     */
    private void populateChunkWithFeatures(Chunk chunk) {
        if (chunk == null || chunk.areFeaturesPopulated()) {
            return; // Already populated or null chunk
        }

        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();

        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int worldX = chunkX * CHUNK_SIZE + x;
                int worldZ = chunkZ * CHUNK_SIZE + z;

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
                        synchronized (randomLock) {
                            if (this.random.nextFloat() < 0.015) { // Increased coal slightly
                                oreType = BlockType.COAL_ORE;
                            } else if (this.random.nextFloat() < 0.008 && y < 50) { // Increased iron slightly, wider range
                                oreType = BlockType.IRON_ORE;
                            }
                        }
                    } else if (biome == BiomeType.RED_SAND_DESERT && (currentBlock == BlockType.RED_SAND || currentBlock == BlockType.STONE || currentBlock == BlockType.MAGMA) && y > 20 && y < surfaceHeight - 5) {
                        // Crystal generation in Volcanic biomes, embedded in Obsidian, Stone or Magma
                        synchronized (randomLock) {
                            if (this.random.nextFloat() < 0.02) { // Chance for Crystal
                                oreType = BlockType.CRYSTAL;
                            }
                        }
                    }
                    
                    if (oreType != null) {
                        this.setBlockAt(worldX, y, worldZ, oreType);
                    }
                }
                
                // Generate Trees (only in PLAINS biome for now)
                if (biome == BiomeType.PLAINS) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.GRASS) { // Ensure tree spawns on grass
                        boolean shouldGenerateTree;
                        synchronized (randomLock) {
                            shouldGenerateTree = (this.random.nextFloat() < 0.01 && surfaceHeight > 64);
                        }
                        if (shouldGenerateTree) {
                            generateTree(chunk, x, surfaceHeight, z);
                        }
                    }
                }
                
                // Generate Flowers on grass surfaces in PLAINS biome
                if (biome == BiomeType.PLAINS && surfaceHeight > 64 && surfaceHeight < WORLD_HEIGHT) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.GRASS && 
                        chunk.getBlock(x, surfaceHeight, z) == BlockType.AIR) {
                        boolean shouldGenerateFlower;
                        synchronized (randomLock) {
                            shouldGenerateFlower = this.random.nextFloat() < 0.08; // 8% chance for flowers
                        }
                        if (shouldGenerateFlower) {
                            BlockType flowerType;
                            synchronized (randomLock) {
                                flowerType = this.random.nextBoolean() ? BlockType.ROSE : BlockType.DANDELION;
                            }
                            this.setBlockAt(worldX, surfaceHeight, worldZ, flowerType);
                        }
                    }
                }
                // No trees in DESERT or VOLCANIC biomes by default
            }
        }
        chunk.setFeaturesPopulated(true);
    }
    
    /**
     * Generates a tree at the specified position.
     */
    private void generateTree(Chunk chunk, int x, int y, int z) { // x, z are local to chunk; y is worldYBase
        // Calculate world coordinates for the base of the trunk
        int worldXBase = chunk.getWorldX(x); // x is localXInOriginChunk
        int worldZBase = chunk.getWorldZ(z); // z is localZInOriginChunk
        int worldYBase = y;                  // y is worldYBase for the bottom of the trunk

        // Check if the top of the tree goes out of world bounds vertically.
        // Trunk is 5 blocks (worldYBase to worldYBase+4), leaves extend to worldYBase+6.
        if (worldYBase + 6 >= WORLD_HEIGHT) {
            return;
        }

        // Removed: Old check that restricted tree trunk origin (x,z) based on CHUNK_SIZE.
        // Trees can now originate near chunk edges and their parts will be placed in correct chunks.

        // Place tree trunk
        for (int dyTrunk = 0; dyTrunk < 5; dyTrunk++) { // 5 blocks high trunk
            if (worldYBase + dyTrunk < WORLD_HEIGHT) { // Ensure trunk block is within height limits
                 this.setBlockAt(worldXBase, worldYBase + dyTrunk, worldZBase, BlockType.WOOD);
            }
        }

        // Place leaves
        int leafRadius = 2; // Leaves spread -2 to +2 blocks from the trunk's center line
        // Leaf layers are relative to worldYBase, starting at an offset of 3 blocks up, to 6 blocks up.
        for (int leafLayerYOffset = 3; leafLayerYOffset <= 6; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;

            // Ensure this leaf layer is within world height (mostly covered by initial check)
            if (currentLeafWorldY >= WORLD_HEIGHT) {
                continue;
            }

            for (int dxLeaf = -leafRadius; dxLeaf <= leafRadius; dxLeaf++) {
                for (int dzLeaf = -leafRadius; dzLeaf <= leafRadius; dzLeaf++) {
                    // Skip the four far corners of the 5x5 leaf square for a rounder canopy
                    if (Math.abs(dxLeaf) == leafRadius && Math.abs(dzLeaf) == leafRadius) {
                        continue;
                    }
                    // Skip the center column (directly above trunk) for the lower two leaf layers (offset 3 and 4)
                    // This makes the tree look less blocky from underneath.
                    if (dxLeaf == 0 && dzLeaf == 0 && leafLayerYOffset < 5) {
                        continue;
                    }

                    // Removed: Old check for leaf x+dx, z+dz being outside local chunk bounds.
                    // this.setBlockAt handles world coordinates and places blocks in correct chunks.
                    this.setBlockAt(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.LEAVES);
                }
            }
        }
    }
    
/**
     * Safely generates a new chunk, registers it, and queues it for mesh building.
     * Handles exceptions during generation/registration and prevents adding null to collections.
     */
    private Chunk safelyGenerateAndRegisterChunk(int chunkX, int chunkZ) {
        ChunkPosition position = new ChunkPosition(chunkX, chunkZ);
        // This check is a safeguard; callers (getChunkAt, getChunksAroundPlayer) 
        // usually check if the chunk exists before calling a generation path.
        if (chunks.containsKey(position)) {
            return chunks.get(position); // Should ideally not happen if callers check first
        }

        try {
            Chunk newGeneratedChunk = generateBareChunk(chunkX, chunkZ);
            
            if (newGeneratedChunk == null) {
                // This indicates a problem in generateChunk if it's designed to return null on error
                // instead of throwing. ConcurrentHashMap cannot store null values.
                System.err.println("CRITICAL: generateChunk returned null for position (" + chunkX + ", " + chunkZ + ")");
                return null; // Cannot register null
            }
            
            // Attempt to add the new chunk to the map.
            // Using putIfAbsent could be an option for stricter concurrency,
            // but current logic has callers check containsKey first.
            chunks.put(position, newGeneratedChunk);
            // Only queue for mesh build if within a certain distance of the player,
            // or if the player object isn't available yet (e.g. initial generation).
            // This prevents cascading mesh builds from chunks generated far away
            // solely for adjacency checks during other chunks' mesh building.
            boolean shouldQueueForMesh = true; // Default to true
            Player player = Game.getPlayer();
            if (player != null) {
                int playerChunkX = (int) Math.floor(player.getPosition().x / CHUNK_SIZE);
                int playerChunkZ = (int) Math.floor(player.getPosition().z / CHUNK_SIZE);
                int distanceToPlayer = Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ));

                // RENDER_DISTANCE + 1 is the zone actively loaded by getChunksAroundPlayer.
                // Chunks generated beyond this for adjacency checks should not be automatically meshed.
                if (distanceToPlayer > RENDER_DISTANCE + 1) {
                    shouldQueueForMesh = false;
                }
            }

            if (shouldQueueForMesh) {
                // Instead of directly adding, use the conditional scheduler
                // which handles the meshDataGenerationScheduledOrInProgress flag.
                conditionallyScheduleMeshBuild(newGeneratedChunk);
            }
            return newGeneratedChunk;
        } catch (Exception e) {
            // Catch any exception from generateChunk or map operations (e.g., if generateChunk throws)
            System.err.println("Exception during chunk generation or registration at (" + chunkX + ", " + chunkZ + "): " + e.getMessage());
            System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
            // Ensure the potentially problematic position is not left in an inconsistent state in 'chunks'
            // if 'put' partially succeeded before an error, though 'put' on CHM is atomic.
            // If an error occurred, the chunk is not considered successfully registered.
            chunks.remove(position); // Clean up if put happened before another error
            return null; // Indicate failure
        }
    }
    /**
     * Generates terrain height for the specified world position.
     */
    private int generateTerrainHeight(int x, int z) {
        // Use multiple octaves of noise for more interesting terrain
        float nx = x / 100.0f;
        float nz = z / 100.0f;
        
        // Base terrain
        float baseHeight = terrainNoise.noise(nx, nz) * 0.5f + 0.5f;
        
        // Hills
        float hillsValue = terrainNoise.noise(nx * 2, nz * 2) * 0.25f;
        
        // Mountains
        float mountainValue = terrainNoise.noise(nx * 0.5f, nz * 0.5f);
        mountainValue = mountainValue * mountainValue * 0.3f;
        
        // Combine noise layers
        float heightValue = baseHeight + hillsValue + mountainValue;
        
        // Scale to world height
        int height = 60 + (int)(heightValue * 40);
        return Math.max(1, Math.min(height, WORLD_HEIGHT - 1));
    }
    
    /**
     * Generates moisture value for determining biomes.
     */
    private float generateMoisture(int x, int z) {
        float nx = x / 200.0f;
        float nz = z / 200.0f;
        
        return terrainNoise.noise(nx + 100, nz + 100) * 0.5f + 0.5f; // Range 0.0 to 1.0
    }
    
    private float generateTemperature(int x, int z) {
        float nx = x / 300.0f; // Different scale for temperature
        float nz = z / 300.0f;
        return temperatureNoise.noise(nx - 50, nz - 50) * 0.5f + 0.5f; // Range 0.0 to 1.0
    }
    
    private BiomeType getBiomeType(int x, int z) {
        float moisture = generateMoisture(x, z);
        float temperature = generateTemperature(x, z);

        if (temperature > 0.65f) { // Hot
            if (moisture < 0.35f) {
                return BiomeType.DESERT;
            } else {
                return BiomeType.RED_SAND_DESERT; // Hot and somewhat moist/varied = Red Sand Desert
            }
        } else if (temperature < 0.35f) { // Cold
            // Could add TUNDRA or SNOWY biomes here later
            return BiomeType.PLAINS; // Default to PLAINS for cold for now
        } else { // Temperate
            if (moisture < 0.3f) {
                return BiomeType.DESERT; // Temperate but dry = also desert like
            } else {
                return BiomeType.PLAINS;
            }
        }
    }
    
    /**
     * Conditionally schedules a chunk for mesh data building if it's not already
     * built, being built, or scheduled. Manages the 'meshDataGenerationScheduledOrInProgress' flag.
     */
    private void conditionallyScheduleMeshBuild(Chunk chunkToBuild) {
        if (chunkToBuild == null) {
            return;
        }

        synchronized (chunkToBuild) {
            // Check if already processed or in a queue/state that doesn't require re-scheduling
            if (chunkToBuild.isMeshGenerated() && chunkToBuild.isDataReadyForGL()) { // Already fully meshed and data applied
                return;
            }
            if (chunkToBuild.isDataReadyForGL() && !chunkToBuild.isMeshGenerated()) { // Data ready for GL, waiting for main thread
                // This state is handled by applyPendingGLUpdates, no need to re-schedule for mesh data gen.
                return;
            }
            if (chunkToBuild.isMeshDataGenerationScheduledOrInProgress()) { // Already scheduled or worker is on it
                return;
            }
            if (chunksToBuildMesh.contains(chunkToBuild) || chunksReadyForGLUpload.contains(chunkToBuild)) { // Already in one of the queues
                return;
            }

            // If we reach here, the chunk needs its mesh data generated.
            chunkToBuild.setMeshDataGenerationScheduledOrInProgress(true);
            chunksToBuildMesh.add(chunkToBuild); // Add to the set for the worker pool to pick up
        }
    }

    /**
     * Submits tasks to the executor to build mesh data for chunks in the queue.
     * This is called from the main game loop.
     */
    private void processChunkMeshBuildRequests() {
        if (chunksToBuildMesh.isEmpty()) {
            return;
        }

        // Process a copy to avoid issues if new chunks are added concurrently
        // (though current design adds from main thread only)
        Set<Chunk> batchToProcess = new HashSet<>(chunksToBuildMesh);
        chunksToBuildMesh.clear(); // Clear the set for next frame's requests

        for (Chunk chunkToProcess : batchToProcess) {
            chunkBuildExecutor.submit(() -> {
                boolean buildSuccess = false;
                try {
                    // The chunkToProcess.meshDataGenerationScheduledOrInProgress was set to true before adding to chunksToBuildMesh
                    chunkToProcess.buildAndPrepareMeshData(this); // This sets dataReadyForGL internally
                    buildSuccess = chunkToProcess.isDataReadyForGL();
                    
                    if (buildSuccess) {
                        chunksReadyForGLUpload.offer(chunkToProcess);
                    }
                } catch (Exception e) {
                    // This catch is primarily for unexpected errors from the submit call itself,
                    // as buildAndPrepareMeshData now catches its internal exceptions.
                    System.err.println("Outer error during mesh build task for chunk at (" + chunkToProcess.getWorldX(0)/CHUNK_SIZE + ", " + chunkToProcess.getWorldZ(0)/CHUNK_SIZE + "): " + e.getMessage());
                    System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
                    buildSuccess = false; // Ensure success is false if an outer exception occurred
                } finally {
                    // CRITICAL: Always reset the flag for the current chunk.
                    synchronized (chunkToProcess) {
                        chunkToProcess.setMeshDataGenerationScheduledOrInProgress(false);
                    }

                    // If the build failed for chunkToProcess, also attempt to refresh its direct XZ neighbors.
                    // This helps if a neighbor was in a problematic state causing this chunk's build to fail.
                    if (!buildSuccess) {
                        System.out.println("Mesh build failed for chunk (" + chunkToProcess.getWorldX(0)/CHUNK_SIZE + ", " + chunkToProcess.getWorldZ(0)/CHUNK_SIZE + "). Refreshing neighbors.");
                        int cx = chunkToProcess.getWorldX(0) / CHUNK_SIZE;
                        int cz = chunkToProcess.getWorldZ(0) / CHUNK_SIZE;
                        int[][] neighborOffsets = {
                            {cx, cz + 1}, {cx, cz - 1},
                            {cx + 1, cz}, {cx - 1, cz}
                        };

                        for (int[] offset : neighborOffsets) {
                            Chunk neighbor = chunks.get(new ChunkPosition(offset[0], offset[1]));
                            if (neighbor != null) {
                                synchronized (neighbor) {
                                    neighbor.setDataReadyForGL(false);
                                    neighbor.setMeshDataGenerationScheduledOrInProgress(false);
                                }
                                conditionallyScheduleMeshBuild(neighbor); // Re-queue the neighbor
                                // System.out.println("Scheduled neighbor (" + offset[0] + ", " + offset[1] + ") for rebuild due to primary chunk failure.");
                            }
                        }
                    }
                }
            });
        }
    }    /**
     * Applies prepared mesh data to OpenGL for chunks that are ready.
     * This must be called from the main game (OpenGL) thread.
     */
    private void applyPendingGLUpdates() {
        Chunk chunkToUpdate;
        // Process a limited number per frame to avoid stutter if many chunks complete at once
        int updatesThisFrame = 0;
        int maxUpdatesPerFrame = 32; // Increased from 8 to 32 to process more chunks per frame

        while ((chunkToUpdate = chunksReadyForGLUpload.poll()) != null && updatesThisFrame < maxUpdatesPerFrame) {
            chunkToUpdate.applyPreparedDataToGL();
            updatesThisFrame++;
        }
    }
      /**
     * Returns chunks around the specified position within render distance.
     */
    public Map<ChunkPosition, Chunk> getChunksAroundPlayer(int playerChunkX, int playerChunkZ) {
        Map<ChunkPosition, Chunk> visibleChunks = new HashMap<>();
        
        // First pass: Generate all chunks that should be visible
        // First pass: Ensure chunks within render distance are loaded and populated
        for (int x = playerChunkX - RENDER_DISTANCE; x <= playerChunkX + RENDER_DISTANCE; x++) {
            for (int z = playerChunkZ - RENDER_DISTANCE; z <= playerChunkZ + RENDER_DISTANCE; z++) {
                ChunkPosition position = new ChunkPosition(x, z);
                Chunk chunk = getChunkAt(x, z); // Gets or creates a bare chunk

                if (chunk != null) {
                    if (!chunk.areFeaturesPopulated()) {
                        populateChunkWithFeatures(chunk);
                        // Mark for mesh rebuild as population changes blocks
                        synchronized (chunk) {
                            chunk.setDataReadyForGL(false);
                            chunk.setMeshDataGenerationScheduledOrInProgress(false);
                        }
                        conditionallyScheduleMeshBuild(chunk);
                    }
                    visibleChunks.put(position, chunk); // Add to visible map
                }
            }
        }
        
        // Second pass: Ensure border chunks (just outside render_distance) exist (as bare chunks) for meshing purposes.
        // These are not added to `visibleChunks` and are not populated here.
        for (int x = playerChunkX - RENDER_DISTANCE - 1; x <= playerChunkX + RENDER_DISTANCE + 1; x++) {
            for (int z = playerChunkZ - RENDER_DISTANCE - 1; z <= playerChunkZ + RENDER_DISTANCE + 1; z++) {
                // Only process chunks that are in the border strip (i.e., not in the main render_distance loop above)
                boolean isInsideRenderDist = (x >= playerChunkX - RENDER_DISTANCE && x <= playerChunkX + RENDER_DISTANCE &&
                                              z >= playerChunkZ - RENDER_DISTANCE && z <= playerChunkZ + RENDER_DISTANCE);
                if (isInsideRenderDist) {
                    continue; // Already handled by the first pass
                }
                
                // If we are here, it's a border chunk. Ensure it exists as at least a bare chunk.
                getChunkAt(x, z); // This will create a bare chunk if it doesn't exist. No need to populate.
            }
        }
        
        return visibleChunks;
    }
      /**
     * Unloads chunks that are too far from the player.
     * This helps manage memory by removing chunks that aren't visible.
     */
    private void unloadDistantChunks() {
        Player player = Game.getPlayer();
        if (player == null) {
            return; // No player to check distance against
        }
        
        // Get player chunk position
        int playerChunkX = (int) Math.floor(player.getPosition().x / CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / CHUNK_SIZE);
        
          // Create a list of chunks to unload to avoid ConcurrentModificationException
        java.util.List<ChunkPosition> chunksToUnload = new java.util.ArrayList<>();
        
        // Create a copy of the chunks map to avoid ConcurrentModificationException
        Map<ChunkPosition, Chunk> chunksCopy = new HashMap<>(chunks);
          
        // Check all loaded chunks to see which ones should be unloaded
        for (Map.Entry<ChunkPosition, Chunk> entry : chunksCopy.entrySet()) {
            ChunkPosition pos = entry.getKey();
            
            // Calculate distance from player (in chunk coordinates)
            int distanceX = Math.abs(pos.getX() - playerChunkX);
            int distanceZ = Math.abs(pos.getZ() - playerChunkZ);
            int chunkDistance = Math.max(distanceX, distanceZ); // Chebyshev distance
            
            // If the chunk is outside the unload distance, mark it for unloading
            if (chunkDistance > UNLOAD_DISTANCE) {
                chunksToUnload.add(pos);
            }
        }          // Unload marked chunks
        for (ChunkPosition pos : chunksToUnload) {
            Chunk chunk = chunks.get(pos);
            if (chunk != null) {
                // Remove from work queues to avoid processing a chunk that's about to be unloaded
                chunksToBuildMesh.remove(chunk);
                chunksReadyForGLUpload.remove(chunk);
                  
                // Clean up OpenGL resources
                chunk.cleanup();
                
                // Remove from collection
                chunks.remove(pos);
            }
        }
        
        // Only log when we unload a significant number of chunks
        if (chunksToUnload.size() > 5) {
            System.out.println("Unloaded " + chunksToUnload.size() + " distant chunks. Total chunks remaining: " + chunks.size());
        }
    }
      /**
     * Cleans up resources when the game exits.
     */
    public void cleanup() {
        // First shutdown the executor service to prevent new tasks
        chunkBuildExecutor.shutdownNow();
        try {
            // Wait for existing tasks to terminate
            if (!chunkBuildExecutor.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
                System.err.println("Chunk builder threads did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Chunk builder shutdown interrupted");
        }
        
        // Clear async processing queues
        chunksToBuildMesh.clear();
        chunksReadyForGLUpload.clear();
        
        // Now clean up chunk resources after ensuring no threads are modifying the collection
        for (Chunk chunk : new HashMap<>(chunks).values()) {
            chunk.cleanup();
        }
        
        // Finally clear the chunks map
        chunks.clear();
    }
    
    /**
     * Returns the total number of loaded chunks.
     * This is used for debugging purposes.
     */
    public int getLoadedChunkCount() {
        return chunks.size();
    }
    
    /**
     * Represents a position of a chunk in the world.
     */
    public static class ChunkPosition {
        private final int x;
        private final int z;
        
        public ChunkPosition(int x, int z) {
            this.x = x;
            this.z = z;
        }
        
        public int getX() {
            return x;
        }
        
        public int getZ() {
            return z;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            
            ChunkPosition that = (ChunkPosition) o;
            return x == that.x && z == that.z;
        }
        
        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + z;
            return result;
        }
    }
}
