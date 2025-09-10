package com.stonebreak.world;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap; // Added
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stonebreak.blocks.BlockDropManager;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.util.MemoryProfiler;
import com.stonebreak.util.SplineInterpolator;

/**
 * Manages the game world and chunks.
 */
public class World {
      // World settings
    public static final int CHUNK_SIZE = 16;
    public static final int WORLD_HEIGHT = 256;
    public static final int SEA_LEVEL = 64;
    private static final int RENDER_DISTANCE = 8;
      // Seed for terrain generation
    private long seed;
    private org.joml.Vector3f spawnPosition; // World spawn position
    private DeterministicRandom deterministicRandom; // New deterministic random for features
    private NoiseGenerator terrainNoise;
    private NoiseGenerator temperatureNoise; // For biome determination
    private NoiseGenerator continentalnessNoise;
    private final SplineInterpolator terrainSpline;
    private final Object randomLock = new Object(); // Lock for synchronizing random access
    
    // Stores all chunks in the world
    private final Map<ChunkPosition, Chunk> chunks;
    
    // Cache for ChunkPosition objects to reduce allocations
    private final Map<Long, ChunkPosition> chunkPositionCache = new ConcurrentHashMap<>();
    private static final int MAX_CHUNK_POSITION_CACHE_SIZE = 10000; // Limit cache size
    
    // Tracks which chunks need mesh updates
    private final ExecutorService chunkBuildExecutor;
    private final Set<Chunk> chunksToBuildMesh; // Chunks needing their mesh data (re)built
    private final Queue<Chunk> chunksReadyForGLUpload; // Chunks with mesh data ready for GL upload
    private final Queue<Chunk> chunksFailedToBuildMesh; // New queue for failed chunks
    private static final int MAX_FAILED_CHUNK_RETRIES = 3; // Limit retries to prevent infinite loops
    private final Map<Chunk, Integer> chunkRetryCount = new ConcurrentHashMap<>(); // Track retry attempts
    private final Queue<Chunk> chunksPendingGpuCleanup = new ConcurrentLinkedQueue<>();
    
    // Chunk Manager
    private final ChunkManager chunkManager;
    
    // Snow layer management
    private final SnowLayerManager snowLayerManager;
    
    // Block drop management
    private final BlockDropManager blockDropManager;
    
    // Loading state management for memory optimization
    private volatile boolean isWorldLoading = true; // Start as true for initial generation
    private volatile long worldLoadingStartTime = System.currentTimeMillis();

    public World() {
        this.seed = System.currentTimeMillis();
        this.deterministicRandom = new DeterministicRandom(seed);
        this.terrainNoise = new NoiseGenerator(seed);
        this.temperatureNoise = new NoiseGenerator(seed + 1); // Use a different seed for temperature
        this.continentalnessNoise = new NoiseGenerator(seed + 2);
        this.chunks = new ConcurrentHashMap<>();
        this.chunkManager = new ChunkManager(this, RENDER_DISTANCE);

        this.terrainSpline = new SplineInterpolator();
        terrainSpline.addPoint(-1.0, 70);  // Islands (changed from 20 to simulate islands above sea level)
        terrainSpline.addPoint(-0.8, 20);  // Deep ocean (new point for preserved deep ocean areas)
        terrainSpline.addPoint(-0.4, 60);  // Approaching coast
        terrainSpline.addPoint(-0.2, 70);  // Just above sea level
        terrainSpline.addPoint(0.1, 75);   // Lowlands
        terrainSpline.addPoint(0.3, 120);  // Mountain foothills
        terrainSpline.addPoint(0.7, 140);  // Common foothills (new point for enhanced foothill generation)
        terrainSpline.addPoint(1.0, 200);  // High peaks
        
        // Initialize thread pool for chunk mesh building
        // Use half available processors, minimum 1
        int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        this.chunkBuildExecutor = Executors.newFixedThreadPool(numThreads);
        
        this.chunksToBuildMesh = ConcurrentHashMap.newKeySet(); // Changed to concurrent set
        this.chunksReadyForGLUpload = new ConcurrentLinkedQueue<>();
        this.chunksFailedToBuildMesh = new ConcurrentLinkedQueue<>(); // Initialize the new queue
        this.snowLayerManager = new SnowLayerManager();
        this.blockDropManager = new BlockDropManager(this);
        
        System.out.println("Creating world with seed: " + seed + ", using " + numThreads + " mesh builder threads.");
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
     * Gets a cached ChunkPosition object to reduce allocations.
     */
    public ChunkPosition getCachedChunkPosition(int x, int z) {
        // Use a long to combine x and z coordinates as a cache key
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        
        // Check cache size and clean if necessary
        if (chunkPositionCache.size() > MAX_CHUNK_POSITION_CACHE_SIZE) {
            cleanupChunkPositionCache();
        }
        
        return chunkPositionCache.computeIfAbsent(key, k -> new ChunkPosition(x, z));
    }
    
    /**
     * Cleans up the chunk position cache when it gets too large.
     * Removes positions that are not currently loaded chunks.
     */
    private void cleanupChunkPositionCache() {
        System.out.println("Cleaning chunk position cache (size: " + chunkPositionCache.size() + ")");
        
        // Create a set of keys for currently loaded chunks
        Set<Long> loadedChunkKeys = new HashSet<>();
        for (ChunkPosition pos : chunks.keySet()) {
            long key = ((long) pos.getX() << 32) | (pos.getZ() & 0xFFFFFFFFL);
            loadedChunkKeys.add(key);
        }
        
        // Remove cache entries that don't correspond to loaded chunks
        chunkPositionCache.entrySet().removeIf(entry -> !loadedChunkKeys.contains(entry.getKey()));
        
        System.out.println("Chunk position cache cleaned (new size: " + chunkPositionCache.size() + ")");
        MemoryProfiler.getInstance().takeSnapshot("after_chunk_position_cache_cleanup");
    }
    
    public void update() {
        // Re-queue chunks that failed their mesh build on a previous frame
        requeueFailedChunks();

        // Use the new chunk loader to manage loading/unloading
        chunkManager.update(Game.getPlayer());

        // CRITICAL: Proactive memory management with different thresholds for loading vs runtime
        int loadedChunks = getLoadedChunkCount();
        
        if (isWorldLoading) {
            // During world loading, be more tolerant of chunk accumulation
            // but still protect against extreme memory usage
            if (loadedChunks > 1200) { // Much higher threshold during loading
                System.out.println("LOADING CRITICAL: " + loadedChunks + " chunks during world loading, triggering emergency cleanup");
                forceUnloadDistantChunks(600); // More aggressive cleanup
            } else if (loadedChunks > 800) { 
                System.out.println("LOADING WARNING: " + loadedChunks + " chunks during world loading, light cleanup");
                forceUnloadDistantChunks(200); // Light cleanup
            }
            // Don't do routine cleanup during loading - let initial generation complete
        } else {
            // Runtime memory management - original aggressive thresholds
            if (loadedChunks > 800) { // Critical emergency - extreme chunk overload
                System.out.println("CRITICAL EMERGENCY: " + loadedChunks + " chunks loaded, triggering massive unloading");
                forceUnloadDistantChunks(400); // Unload up to 400 chunks
            } else if (loadedChunks > 500) { // Emergency threshold - well above normal 361 chunk max
                System.out.println("EMERGENCY: " + loadedChunks + " chunks loaded, triggering emergency unloading");
                forceUnloadDistantChunks(200); // Unload up to 200 chunks
            } else if (loadedChunks > 400) { // Warning threshold - above expected maximum
                // Clean up position cache proactively
                if (chunkPositionCache.size() > loadedChunks * 2) {
                    cleanupChunkPositionCache();
                    System.out.println("WARNING: " + loadedChunks + " chunks loaded, cleaned position cache");
                }
                // Force GC on high chunk count (less frequent logging)
                if (loadedChunks % 50 == 0) {
                    Game.forceGCAndReport("Proactive GC at " + loadedChunks + " chunks");
                }
            }
        }

        // Process requests to build mesh data (async)
        processChunkMeshBuildRequests();

        // Apply mesh data to GL objects on the main thread
        
        // Process GPU resource cleanups on the main thread
        // Update block drops
        blockDropManager.update(Game.getDeltaTime());
    }

    public void updateMainThread() {
        applyPendingGLUpdates();
        processGpuCleanupQueue();
    }
    public void ensureChunkIsReadyForRender(int cx, int cz) {
        ChunkPosition position = getCachedChunkPosition(cx, cz);
        Chunk chunk = chunks.get(position);

        if (chunk == null) {
            // This should be rare as the loader calls getChunkAt first, but handle it just in case.
            chunk = getChunkAt(cx, cz);
            if (chunk == null) {
                return; // Failed to create/get chunk
            }
        }

        // 1. Populate features if not already done
        if (!chunk.areFeaturesPopulated()) {
            populateChunkWithFeatures(chunk);
            // Population changes blocks, so a mesh rebuild is necessary.
            synchronized (chunk) {
                chunk.setDataReadyForGL(false);
                chunk.setMeshDataGenerationScheduledOrInProgress(false);
            }
            conditionallyScheduleMeshBuild(chunk);
        }

        // 2. Check mesh status and schedule build if needed
        boolean isMeshReady = chunk.isMeshGenerated() && chunk.isDataReadyForGL();
        if (!isMeshReady) {
            // It might already be scheduled, but this ensures it gets picked up
            // if it was missed or a previous build failed.
            conditionallyScheduleMeshBuild(chunk);
        }

        // 3. Also check and schedule direct neighbors for mesh builds.
        // This is crucial for seamless terrain visuals.
        int[][] neighborOffsets = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        for (int[] offset : neighborOffsets) {
            ChunkPosition neighborPos = getCachedChunkPosition(cx + offset[0], cz + offset[1]);
            Chunk neighbor = chunks.get(neighborPos);
            
            // Only trigger a mesh build if the neighbor exists and is populated but not yet meshed.
            // If the neighbor is not populated, it will be handled when its turn comes.
            if (neighbor != null && neighbor.areFeaturesPopulated()) {
                boolean isNeighborMeshReady = neighbor.isMeshGenerated() && neighbor.isDataReadyForGL();
                if (!isNeighborMeshReady) {
                    conditionallyScheduleMeshBuild(neighbor);
                }
            }
        }
    }
    
    /**
     * Gets the chunk at the specified position.
     * If the chunk doesn't exist, it will be generated.
     * Returns null if chunk generation fails critically.
     */
    public Chunk getChunkAt(int x, int z) {
        ChunkPosition position = getCachedChunkPosition(x, z);
        Chunk chunk = chunks.get(position);
        
        if (chunk == null) {
            try {
                // Use the new safe method for generation, registration, and queueing
                chunk = safelyGenerateAndRegisterChunk(x, z);
            } catch (ChunkGenerationException e) {
                // Log the detailed error but return null to indicate failure
                System.err.println("CRITICAL: Failed to generate chunk at (" + x + ", " + z + "): " + e.getMessage());
                // During world loading, this failure should be detected and handled by loading validation
                return null;
            }
        }
        
        return chunk;
    }
    
    /**
     * Checks if a chunk exists at the specified position.
     */
    public boolean hasChunkAt(int x, int z) {
        return chunks.containsKey(getCachedChunkPosition(x, z));
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
        return setBlockAt(x, y, z, blockType, false); // Default to non-player modification
    }
    
    /**
     * Sets the block type at the specified world position with player modification tracking.
     * @param x World X coordinate
     * @param y World Y coordinate  
     * @param z World Z coordinate
     * @param blockType The block type to set
     * @param playerModified True if this modification came from player actions, false for world generation
     * @return true if the block was successfully set, false otherwise (e.g., out of bounds).
     */
    public boolean setBlockAt(int x, int y, int z, BlockType blockType, boolean playerModified) {
        if (y < 0 || y >= WORLD_HEIGHT) {
            return false;
        }
        
        int chunkX = Math.floorDiv(x, CHUNK_SIZE);
        int chunkZ = Math.floorDiv(z, CHUNK_SIZE);
        
        Chunk chunk = getChunkAt(chunkX, chunkZ);
        
        if (chunk == null) {
            return false; // Failed to get/create chunk
        }
        
        int localX = Math.floorMod(x, CHUNK_SIZE);
        int localZ = Math.floorMod(z, CHUNK_SIZE);
        
        chunk.setBlock(localX, y, localZ, blockType, playerModified);

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
            neighbor = chunks.get(getCachedChunkPosition(chunkX - 1, chunkZ));
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
            neighbor = chunks.get(getCachedChunkPosition(chunkX + 1, chunkZ));
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
            neighbor = chunks.get(getCachedChunkPosition(chunkX, chunkZ - 1));
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
            neighbor = chunks.get(getCachedChunkPosition(chunkX, chunkZ + 1));
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
    * Sets the block type at the specified world position, marking it as a player modification.
    * This should be used for all player-initiated block changes (breaking, placing, etc.)
    * @param x World X coordinate
    * @param y World Y coordinate
    * @param z World Z coordinate
    * @param blockType The block type to set
    * @return true if the block was successfully set, false otherwise
    */
   public boolean setBlockByPlayer(int x, int y, int z, BlockType blockType) {
       return setBlockAt(x, y, z, blockType, true);
   }
   
   /**
     * Generates only the bare terrain for a new chunk (no features like ores or trees).
     * Uses chunk.setBlock() for local block placement.
     */
    private Chunk generateBareChunk(int chunkX, int chunkZ) {
        updateLoadingProgress("Generating Base Terrain Shape");
        Chunk chunk = new Chunk(chunkX, chunkZ);
        
        // Generate terrain
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                // Calculate absolute world coordinates
                int worldX = chunkX * CHUNK_SIZE + x;
                int worldZ = chunkZ * CHUNK_SIZE + z;
                
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
                        if (biome == BiomeType.RED_SAND_DESERT && y < height - 10 && 
                            deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "magma", 0.6f)) {
                            blockType = BlockType.MAGMA; // More magma deeper in volcanic areas
                        } else {
                            blockType = BlockType.STONE;
                        }                    } else if (y < height - 1) {
                        // Sub-surface layer
                        blockType = (biome != null) ? switch (biome) {
                            case RED_SAND_DESERT -> BlockType.RED_SANDSTONE;
                            case DESERT -> BlockType.SANDSTONE;
                            case PLAINS -> BlockType.DIRT;
                            case SNOWY_PLAINS -> BlockType.DIRT;
                            default -> BlockType.DIRT;
                        } : BlockType.DIRT;                    } else if (y < height) {
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

        updateLoadingProgress("Adding Surface Decorations & Details");
        
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
                        // Use deterministic generation based on 3D world coordinates
                        if (deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "coal_ore", 0.015f)) {
                            oreType = BlockType.COAL_ORE;
                        } else if (deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "iron_ore", 0.008f) && y < 50) {
                            oreType = BlockType.IRON_ORE;
                        }
                    } else if (biome == BiomeType.RED_SAND_DESERT && (currentBlock == BlockType.RED_SAND || currentBlock == BlockType.STONE || currentBlock == BlockType.MAGMA) && y > 20 && y < surfaceHeight - 5) {
                        // Crystal generation in Volcanic biomes, embedded in Obsidian, Stone or Magma
                        if (deterministicRandom.shouldGenerate3D(worldX, y, worldZ, "crystal", 0.02f)) {
                            oreType = BlockType.CRYSTAL;
                        }
                    }
                    
                    if (oreType != null) {
                        this.setBlockAt(worldX, y, worldZ, oreType);
                    }
                }
                
                // Generate Trees (in PLAINS and SNOWY_PLAINS biomes)
                if (biome == BiomeType.PLAINS) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.GRASS) { // Ensure tree spawns on grass
                        // Use scrambled coordinates to break linear patterns
                        int scrambledX = scrambleCoordinate(worldX, worldZ, "tree_x");
                        int scrambledZ = scrambleCoordinate(worldZ, worldX, "tree_z");
                        if (deterministicRandom.shouldGenerate(scrambledX, scrambledZ, "tree", 0.03f) && surfaceHeight > 64) {
                            // Determine tree type: 60% regular oak, 40% elm trees
                            if (deterministicRandom.shouldGenerate(worldX, worldZ, "elm_tree", 0.4f)) {
                                generateElmTree(chunk, x, surfaceHeight, z);
                            } else {
                                generateTree(chunk, x, surfaceHeight, z);
                            }
                        }
                    }
                } else if (biome == BiomeType.SNOWY_PLAINS) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SNOWY_DIRT) { // Pine trees spawn on snowy dirt
                        // Use scrambled coordinates to break linear patterns
                        int scrambledX = scrambleCoordinate(worldX, worldZ, "pine_x");
                        int scrambledZ = scrambleCoordinate(worldZ, worldX, "pine_z");
                        if (deterministicRandom.shouldGenerate(scrambledX, scrambledZ, "pine_tree", 0.04f) && surfaceHeight > 64) {
                            generatePineTree(chunk, x, surfaceHeight, z);
                        }
                    }
                }
                
                // Generate Flowers on grass surfaces in PLAINS biome
                if (biome == BiomeType.PLAINS && surfaceHeight > 64 && surfaceHeight < WORLD_HEIGHT) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.GRASS && 
                        chunk.getBlock(x, surfaceHeight, z) == BlockType.AIR) {
                        // Use scrambled coordinates to break linear patterns
                        int scrambledX = scrambleCoordinate(worldX, worldZ, "flower_x");
                        int scrambledZ = scrambleCoordinate(worldZ, worldX, "flower_z");
                        if (deterministicRandom.shouldGenerate(scrambledX, scrambledZ, "flower", 0.03f)) {
                            BlockType flowerType = deterministicRandom.getBoolean(worldX, worldZ, "flower_type") ? BlockType.ROSE : BlockType.DANDELION;
                            this.setBlockAt(worldX, surfaceHeight, worldZ, flowerType);
                        }
                    }
                }
                
                // Generate Ice patches and Snow layers in SNOWY_PLAINS biome
                if (biome == BiomeType.SNOWY_PLAINS && surfaceHeight > 64 && surfaceHeight < WORLD_HEIGHT) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SNOWY_DIRT && 
                        chunk.getBlock(x, surfaceHeight, z) == BlockType.AIR) {
                        
                        if (deterministicRandom.shouldGenerate(worldX, worldZ, "ice", 0.03f)) { // 3% chance for ice patches
                            this.setBlockAt(worldX, surfaceHeight, worldZ, BlockType.ICE);
                        } else if (deterministicRandom.shouldGenerate(worldX, worldZ, "snow_layer", 0.05f)) { // 5% chance for snow layers (not cumulative)
                            this.setBlockAt(worldX, surfaceHeight, worldZ, BlockType.SNOW);
                            // Set initial snow layer count (1-3 layers deterministically)
                            int layers = 1 + deterministicRandom.getInt(worldX, worldZ, "snow_layer_count", 3); // 1, 2, or 3 layers
                            snowLayerManager.setSnowLayers(worldX, surfaceHeight, worldZ, layers);
                        }
                    }
                }
                // No trees in DESERT or VOLCANIC biomes by default
            }
        }
        
        // Spawn cows in PLAINS biome (10% chance per chunk)
        BiomeType chunkBiome = getBiomeType(chunkX * CHUNK_SIZE + CHUNK_SIZE / 2, chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2);
        if (chunkBiome == BiomeType.PLAINS) {
            // Use chunk coordinates for cow spawning determination
            int chunkCenterX = chunkX * CHUNK_SIZE + CHUNK_SIZE / 2;
            int chunkCenterZ = chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2;
            
            if (deterministicRandom.shouldGenerate(chunkCenterX, chunkCenterZ, "cow_spawn", 0.1f)) {
                spawnCowsInChunk(chunk);
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
     * Generates a pine tree at the specified position.
     */
    private void generatePineTree(Chunk chunk, int x, int y, int z) { // x, z are local to chunk; y is worldYBase
        // Calculate world coordinates for the base of the trunk
        int worldXBase = chunk.getWorldX(x);
        int worldZBase = chunk.getWorldZ(z);
        int worldYBase = y;

        // Pine trees are taller - check if the top goes out of world bounds
        // Trunk is 7 blocks, leaves extend to worldYBase+8
        if (worldYBase + 8 >= WORLD_HEIGHT) {
            return;
        }

        // Place pine tree trunk (darker wood)
        for (int dyTrunk = 0; dyTrunk < 7; dyTrunk++) { // 7 blocks high trunk
            if (worldYBase + dyTrunk < WORLD_HEIGHT) {
                this.setBlockAt(worldXBase, worldYBase + dyTrunk, worldZBase, BlockType.PINE);
            }
        }

        // Place snowy leaves in a more conical shape
        // Bottom layer (offset 3-4): radius 2
        for (int leafLayerYOffset = 3; leafLayerYOffset <= 4; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WORLD_HEIGHT) continue;

            int leafRadius = 2;
            for (int dxLeaf = -leafRadius; dxLeaf <= leafRadius; dxLeaf++) {
                for (int dzLeaf = -leafRadius; dzLeaf <= leafRadius; dzLeaf++) {
                    // Skip corners for rounder shape
                    if (Math.abs(dxLeaf) == leafRadius && Math.abs(dzLeaf) == leafRadius) {
                        continue;
                    }
                    // Skip center column for trunk
                    if (dxLeaf == 0 && dzLeaf == 0) {
                        continue;
                    }
                    this.setBlockAt(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.SNOWY_LEAVES);
                }
            }
        }

        // Middle layer (offset 5-6): radius 1
        for (int leafLayerYOffset = 5; leafLayerYOffset <= 6; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WORLD_HEIGHT) continue;

            int leafRadius = 1;
            for (int dxLeaf = -leafRadius; dxLeaf <= leafRadius; dxLeaf++) {
                for (int dzLeaf = -leafRadius; dzLeaf <= leafRadius; dzLeaf++) {
                    // Skip center column for trunk
                    if (dxLeaf == 0 && dzLeaf == 0) {
                        continue;
                    }
                    this.setBlockAt(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.SNOWY_LEAVES);
                }
            }
        }

        // Top layer (offset 7-8): just around the top
        for (int leafLayerYOffset = 7; leafLayerYOffset <= 8; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WORLD_HEIGHT) continue;

            // Very small cap
            for (int dxLeaf = -1; dxLeaf <= 1; dxLeaf++) {
                for (int dzLeaf = -1; dzLeaf <= 1; dzLeaf++) {
                    // Only place on the sides for the very top
                    if (leafLayerYOffset == 8 && (dxLeaf == 0 && dzLeaf == 0)) {
                        continue; // Skip center for very top
                    }
                    if (leafLayerYOffset == 7 && (dxLeaf == 0 && dzLeaf == 0)) {
                        continue; // Skip center for trunk
                    }
                    this.setBlockAt(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.SNOWY_LEAVES);
                }
            }
        }
    }
    
    /**
     * Generates an elm tree at the specified position.
     * Elm trees have the characteristic vase shape - wide canopy supported by branching trunk.
     */
    private void generateElmTree(Chunk chunk, int x, int y, int z) {
        int worldXBase = chunk.getWorldX(x);
        int worldZBase = chunk.getWorldZ(z);
        int worldYBase = y;

        // Elm trees are larger - trunk is 8-12 blocks, canopy extends to worldYBase+16
        // Deterministically vary height based on position
        int trunkHeight = 8 + deterministicRandom.getInt(worldXBase, worldZBase, "elm_trunk_height", 5); // 8-12 blocks tall trunk
        
        if (worldYBase + trunkHeight + 6 >= WORLD_HEIGHT) {
            return; // Tree would exceed world height
        }

        // Place elm trunk - straight up with some branching at the top
        for (int dyTrunk = 0; dyTrunk < trunkHeight; dyTrunk++) {
            if (worldYBase + dyTrunk < WORLD_HEIGHT) {
                this.setBlockAt(worldXBase, worldYBase + dyTrunk, worldZBase, BlockType.ELM_WOOD_LOG);
            }
        }
        
        // Add branch structure at top of trunk (characteristic elm branching)
        // Elm trees branch out near the top, creating the vase shape
        int branchLevel = worldYBase + trunkHeight - 3; // Start branching 3 blocks from top
        if (branchLevel + 3 < WORLD_HEIGHT) {
            // Add horizontal branches for vase shape
            for (int branchY = branchLevel; branchY < branchLevel + 3; branchY++) {
                // Create 4 main branches extending outward
                this.setBlockAt(worldXBase + 1, branchY, worldZBase, BlockType.ELM_WOOD_LOG); // East branch
                this.setBlockAt(worldXBase - 1, branchY, worldZBase, BlockType.ELM_WOOD_LOG); // West branch
                this.setBlockAt(worldXBase, branchY, worldZBase + 1, BlockType.ELM_WOOD_LOG); // South branch
                this.setBlockAt(worldXBase, branchY, worldZBase - 1, BlockType.ELM_WOOD_LOG); // North branch
                
                // Add diagonal branches for fuller structure
                if (branchY == branchLevel + 1) { // Middle level only
                    this.setBlockAt(worldXBase + 1, branchY, worldZBase + 1, BlockType.ELM_WOOD_LOG);
                    this.setBlockAt(worldXBase + 1, branchY, worldZBase - 1, BlockType.ELM_WOOD_LOG);
                    this.setBlockAt(worldXBase - 1, branchY, worldZBase + 1, BlockType.ELM_WOOD_LOG);
                    this.setBlockAt(worldXBase - 1, branchY, worldZBase - 1, BlockType.ELM_WOOD_LOG);
                }
            }
        }

        // Place elm leaves in characteristic wide, vase-like canopy
        // Elm trees have very wide canopies - up to 80 feet spread in real life
        int leafRadius = 4; // Large canopy radius for elm's characteristic wide spread
        
        // Bottom leaf layer - fullest and widest (characteristic of elm's umbrella shape)
        for (int leafLayerYOffset = trunkHeight - 1; leafLayerYOffset <= trunkHeight + 2; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WORLD_HEIGHT) continue;

            for (int dxLeaf = -leafRadius; dxLeaf <= leafRadius; dxLeaf++) {
                for (int dzLeaf = -leafRadius; dzLeaf <= leafRadius; dzLeaf++) {
                    // Create the characteristic elm canopy shape - wider at edges, fuller overall
                    float distFromCenter = (float) Math.sqrt(dxLeaf * dxLeaf + dzLeaf * dzLeaf);
                    
                    // Skip only the very far corners for a more natural rounded shape
                    if (distFromCenter > leafRadius * 0.9f) {
                        continue;
                    }
                    
                    // Skip center column where trunk/branches are (but only in lower layers)
                    if (dxLeaf == 0 && dzLeaf == 0 && leafLayerYOffset < trunkHeight + 1) {
                        continue;
                    }
                    
                    // Add some randomness to leaf placement for more natural look
                    boolean shouldPlaceLeaf = true;
                    if (distFromCenter > leafRadius * 0.7f) {
                        shouldPlaceLeaf = deterministicRandom.getFloat(worldXBase + dxLeaf, worldZBase + dzLeaf, "elm_outer_leaf") > 0.3f; // 70% chance for outer leaves
                    }
                    
                    if (shouldPlaceLeaf) {
                        this.setBlockAt(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.ELM_LEAVES);
                    }
                }
            }
        }

        // Upper leaf layers - gradually smaller but still maintaining elm's broad canopy
        for (int leafLayerYOffset = trunkHeight + 3; leafLayerYOffset <= trunkHeight + 5; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WORLD_HEIGHT) continue;

            int upperRadius = 3; // Slightly smaller for upper layers
            for (int dxLeaf = -upperRadius; dxLeaf <= upperRadius; dxLeaf++) {
                for (int dzLeaf = -upperRadius; dzLeaf <= upperRadius; dzLeaf++) {
                    float distFromCenter = (float) Math.sqrt(dxLeaf * dxLeaf + dzLeaf * dzLeaf);
                    
                    if (distFromCenter > upperRadius * 0.8f) {
                        continue;
                    }
                    
                    // Random gaps in upper canopy
                    boolean shouldPlaceLeaf = deterministicRandom.getFloat(worldXBase + dxLeaf, worldZBase + dzLeaf, "elm_upper_leaf") > 0.2f; // 80% chance
                    
                    if (shouldPlaceLeaf) {
                        this.setBlockAt(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.ELM_LEAVES);
                    }
                }
            }
        }

        // Top crown - small cluster at very top
        for (int leafLayerYOffset = trunkHeight + 6; leafLayerYOffset <= trunkHeight + 6; leafLayerYOffset++) {
            int currentLeafWorldY = worldYBase + leafLayerYOffset;
            if (currentLeafWorldY >= WORLD_HEIGHT) continue;

            for (int dxLeaf = -1; dxLeaf <= 1; dxLeaf++) {
                for (int dzLeaf = -1; dzLeaf <= 1; dzLeaf++) {
                    if (Math.abs(dxLeaf) == 1 && Math.abs(dzLeaf) == 1) continue; // Skip corners
                    this.setBlockAt(worldXBase + dxLeaf, currentLeafWorldY, worldZBase + dzLeaf, BlockType.ELM_LEAVES);
                }
            }
        }
    }
    
    /**
     * Spawns 1-4 cows in a plains chunk at valid grass locations.
     */
    private void spawnCowsInChunk(Chunk chunk) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;
        
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        
        // Determine number of cows to spawn (1-4) deterministically
        int chunkCenterX = chunkX * CHUNK_SIZE + CHUNK_SIZE / 2;
        int chunkCenterZ = chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2;
        int cowCount = 1 + deterministicRandom.getInt(chunkCenterX, chunkCenterZ, "cow_count", 4); // 1, 2, 3, or 4 cows
        
        int spawned = 0;
        int attempts = 0;
        int maxAttempts = 20; // Limit attempts to prevent infinite loops
        
        while (spawned < cowCount && attempts < maxAttempts) {
            attempts++;
            
            // Deterministic position within chunk based on spawn attempt
            int localX = deterministicRandom.getInt(chunkCenterX, chunkCenterZ, "cow_local_x_" + attempts, CHUNK_SIZE);
            int localZ = deterministicRandom.getInt(chunkCenterX, chunkCenterZ, "cow_local_z_" + attempts, CHUNK_SIZE);
            
            int worldX = chunkX * CHUNK_SIZE + localX;
            int worldZ = chunkZ * CHUNK_SIZE + localZ;
            
            // Find surface height
            int surfaceY = 0;
            for (int y = WORLD_HEIGHT - 1; y >= 0; y--) {
                if (chunk.getBlock(localX, y, localZ) != BlockType.AIR) {
                    surfaceY = y + 1; // First air block above ground
                    break;
                }
            }
            
            // Check if valid spawn location
            if (isValidCowSpawnLocation(chunk, localX, surfaceY, localZ)) {
                // Spawn cow at this location
                org.joml.Vector3f spawnPos = new org.joml.Vector3f(
                    worldX + 0.5f, // Center of block
                    surfaceY,
                    worldZ + 0.5f  // Center of block
                );
                
                // Select deterministic texture variant for world generation cow spawning
                String[] variants = {"default", "angus", "highland"};
                int variantIndex = deterministicRandom.getInt(worldX, worldZ, "cow_variant", variants.length);
                String textureVariant = variants[variantIndex];
                entityManager.spawnCowWithVariant(spawnPos, textureVariant);
                spawned++;
            }
        }
    }
    
    /**
     * Checks if the given location is valid for cow spawning.
     * Requires grass block below, air blocks above, and not in water.
     */
    private boolean isValidCowSpawnLocation(Chunk chunk, int localX, int y, int localZ) {
        // Check bounds
        if (localX < 0 || localX >= CHUNK_SIZE || localZ < 0 || localZ >= CHUNK_SIZE) {
            return false;
        }
        if (y < 1 || y >= WORLD_HEIGHT - 1) {
            return false;
        }
        
        // Check ground block (must be grass)
        BlockType groundBlock = chunk.getBlock(localX, y - 1, localZ);
        if (groundBlock != BlockType.GRASS) {
            return false;
        }
        
        // Check spawn space (must be air for cow's height)
        BlockType spawnBlock = chunk.getBlock(localX, y, localZ);
        BlockType aboveBlock = chunk.getBlock(localX, y + 1, localZ);
        
        return spawnBlock == BlockType.AIR && aboveBlock == BlockType.AIR;
    }
    
/**
     * Safely generates a new chunk, registers it, and queues it for mesh building.
     * Handles exceptions during generation/registration and prevents adding null to collections.
     * Now throws ChunkGenerationException to propagate critical errors up the call stack.
     */
    private Chunk safelyGenerateAndRegisterChunk(int chunkX, int chunkZ) throws ChunkGenerationException {
        ChunkPosition position = getCachedChunkPosition(chunkX, chunkZ);
        // This check is a safeguard; callers (getChunkAt, getChunksAroundPlayer) 
        // usually check if the chunk exists before calling a generation path.
        if (chunks.containsKey(position)) {
            return chunks.get(position); // Should ideally not happen if callers check first
        }

        try {
            // Track chunk allocation
            MemoryProfiler.getInstance().incrementAllocation("Chunk");
            
            Chunk newGeneratedChunk = generateBareChunk(chunkX, chunkZ);
            
            if (newGeneratedChunk == null) {
                // This indicates a problem in generateChunk if it's designed to return null on error
                // instead of throwing. ConcurrentHashMap cannot store null values.
                String errorMsg = "CRITICAL: generateBareChunk returned null for position (" + chunkX + ", " + chunkZ + ")";
                System.err.println(errorMsg);
                throw new ChunkGenerationException(errorMsg, chunkX, chunkZ);
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
        } catch (ChunkGenerationException e) {
            // Re-throw our custom exception to preserve the error context
            chunks.remove(position); // Clean up if put happened before another error
            throw e;
        } catch (Exception e) {
            // Catch any other exception from generateChunk or map operations
            System.err.println("Exception during chunk generation or registration at (" + chunkX + ", " + chunkZ + "): " + e.getMessage());
            System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
            // Ensure the potentially problematic position is not left in an inconsistent state in 'chunks'
            // if 'put' partially succeeded before an error, though 'put' on CHM is atomic.
            // If an error occurred, the chunk is not considered successfully registered.
            chunks.remove(position); // Clean up if put happened before another error
            
            // Throw ChunkGenerationException to propagate the error properly
            throw new ChunkGenerationException("Chunk generation failed: " + e.getMessage(), chunkX, chunkZ, e);
        }
    }
    /**
     * Scrambles coordinates to break linear generation patterns while maintaining determinism.
     * Uses a simple hash-based approach tied to the world seed.
     */
    private int scrambleCoordinate(int coord, int otherCoord, String feature) {
        // Create a pseudo-random offset based on world seed, coordinates, and feature
        long hash = seed;
        hash = hash * 31L + coord;
        hash = hash * 31L + otherCoord;
        hash = hash * 31L + feature.hashCode();
        
        // Apply a large pseudo-random offset to break patterns
        return coord + (int)(hash % 10000);
    }
    
    /**
     * Generates terrain height for the specified world position.
     */
    private int generateTerrainHeight(int x, int z) {
        float continentalness = continentalnessNoise.noise(x / 800.0f, z / 800.0f);
        int height = (int) terrainSpline.interpolate(continentalness);
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
     * Gets the continentalness value at the specified world position.
     * This is the same value used for terrain height generation.
     */
    public float getContinentalnessAt(int x, int z) {
        return continentalnessNoise.noise(x / 800.0f, z / 800.0f);
    }
    
    /**
     * Conditionally schedules a chunk for mesh data building if it's not already
     * built, being built, or scheduled. Manages the 'meshDataGenerationScheduledOrInProgress' flag.
     * Now includes enhanced race condition protection.
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
            
            // Additional safety: ensure thread-safe addition to the concurrent collection
            boolean addedSuccessfully = chunksToBuildMesh.add(chunkToBuild);
            if (!addedSuccessfully) {
                // This shouldn't happen with a Set, but if it does, reset the flag
                System.err.println("WARNING: Failed to add chunk to mesh build queue (already present?) - resetting flag");
                chunkToBuild.setMeshDataGenerationScheduledOrInProgress(false);
            }
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

                    // If the build failed for chunkToProcess, add to retry queue with limits
                    if (!buildSuccess) {
                        int retryCount = chunkRetryCount.getOrDefault(chunkToProcess, 0);
                        if (retryCount < MAX_FAILED_CHUNK_RETRIES) {
                            chunkRetryCount.put(chunkToProcess, retryCount + 1);
                            chunksFailedToBuildMesh.offer(chunkToProcess);
                            // System.out.println("Mesh build failed for chunk (" + chunkToProcess.getChunkX() + ", " + chunkToProcess.getChunkZ() + "). Retry " + (retryCount + 1) + "/" + MAX_FAILED_CHUNK_RETRIES);
                        } else {
                            // Max retries reached, remove from retry tracking and log warning
                            chunkRetryCount.remove(chunkToProcess);
                            System.err.println("WARNING: Chunk (" + chunkToProcess.getChunkX() + ", " + chunkToProcess.getChunkZ() + ") failed mesh build after " + MAX_FAILED_CHUNK_RETRIES + " retries. Giving up.");
                            MemoryProfiler.getInstance().incrementAllocation("FailedChunk");
                        }
                    } else {
                        // Build succeeded, remove from retry tracking if it was there
                        chunkRetryCount.remove(chunkToProcess);
                    }
                }
            });
        }
    }
    
    /**
     * Re-queues chunks that failed to build their mesh on a previous frame.
     * This provides a "second pass" for chunks that might have failed due to temporary issues (e.g., neighbor not ready).
     */
    private void requeueFailedChunks() {
        Chunk failedChunk;
        while ((failedChunk = chunksFailedToBuildMesh.poll()) != null) {
            // Reset flags and re-schedule it for a mesh build.
            // The conditionallyScheduleMeshBuild method will handle the logic
            // of adding it to the chunksToBuildMesh set.
            synchronized (failedChunk) {
                failedChunk.setDataReadyForGL(false);
                failedChunk.setMeshDataGenerationScheduledOrInProgress(false);
            }
            conditionallyScheduleMeshBuild(failedChunk);
             // System.out.println("Re-queueing failed chunk (" + failedChunk.getChunkX() + ", " + failedChunk.getChunkZ() + ") for a new build attempt.");
        }
    }

    /**
     * Applies prepared mesh data to OpenGL for chunks that are ready.
     * This must be called from the main game (OpenGL) thread.
     * 
     * OPTIMIZED: Now uses frame-time aware batch sizing to eliminate buffering.
     */
    private void applyPendingGLUpdates() {
        Chunk chunkToUpdate;
        // Process a limited number per frame to avoid stutter if many chunks complete at once
        int updatesThisFrame = 0;
        
        // Use the optimized batch size that considers both memory usage AND frame performance
        int maxUpdatesPerFrame = ChunkManager.getOptimizedGLBatchSize();

        while ((chunkToUpdate = chunksReadyForGLUpload.poll()) != null && updatesThisFrame < maxUpdatesPerFrame) {
            try {
                chunkToUpdate.applyPreparedDataToGL();
                updatesThisFrame++;
            } catch (Exception e) {
                System.err.println("CRITICAL: Exception during applyPreparedDataToGL for chunk (" + chunkToUpdate.getChunkX() + ", " + chunkToUpdate.getChunkZ() + ")");
                System.err.println("Time: " + java.time.LocalDateTime.now());
                System.err.println("Updates this frame: " + updatesThisFrame + "/" + maxUpdatesPerFrame);
                System.err.println("Queue size: " + chunksReadyForGLUpload.size());
                System.err.println("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB used");
                System.err.println("Exception: " + e.getMessage());
                System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
                
                // Try to save crash log to file
                try (java.io.FileWriter fw = new java.io.FileWriter("chunk_gl_errors.txt", true)) {
                    fw.write("=== CHUNK GL ERROR " + java.time.LocalDateTime.now() + " ===\n");
                    fw.write("Chunk: (" + chunkToUpdate.getChunkX() + ", " + chunkToUpdate.getChunkZ() + ")\n");
                    fw.write("Updates: " + updatesThisFrame + "/" + maxUpdatesPerFrame + "\n");
                    fw.write("Queue size: " + chunksReadyForGLUpload.size() + "\n");
                    fw.write("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB\n");
                    fw.write("Exception: " + e.getMessage() + "\n");
                    fw.write("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()) + "\n\n");
                } catch (java.io.IOException logEx) {
                    System.err.println("Failed to write chunk GL error log: " + logEx.getMessage());
                }
                
                // Continue processing other chunks instead of crashing
            }
        }
        
        // Log memory usage when processing many GL updates or when memory is high
        if (updatesThisFrame >= 16 || ChunkManager.isHighMemoryPressure()) {
            Game.logDetailedMemoryInfo("After processing " + updatesThisFrame + " GL updates (optimized batch size: " + maxUpdatesPerFrame + ")");
        }
    }
      /**
     * Returns chunks around the specified position within render distance.
     * Now includes loading state validation to detect critical chunk generation failures.
     */
    public Map<ChunkPosition, Chunk> getChunksAroundPlayer(int playerChunkX, int playerChunkZ) {
        Map<ChunkPosition, Chunk> visibleChunks = new HashMap<>();
        java.util.List<String> failedChunks = new java.util.ArrayList<>();
        
        // First pass: Generate all chunks that should be visible
        // First pass: Ensure chunks within render distance are loaded and populated
        for (int x = playerChunkX - RENDER_DISTANCE; x <= playerChunkX + RENDER_DISTANCE; x++) {
            for (int z = playerChunkZ - RENDER_DISTANCE; z <= playerChunkZ + RENDER_DISTANCE; z++) {
                ChunkPosition position = getCachedChunkPosition(x, z);
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
                } else {
                    // Critical chunk generation failure - record it for validation
                    failedChunks.add("(" + x + ", " + z + ")");
                }
            }
        }
        
        // Validate critical chunk loading - fail fast if too many chunks failed
        if (!failedChunks.isEmpty()) {
            String errorMsg = "CRITICAL: Failed to generate " + failedChunks.size() + " chunks during world loading: " + failedChunks;
            System.err.println(errorMsg);
            
            // If more than 25% of chunks failed, this indicates a serious world loading problem
            int totalChunks = (RENDER_DISTANCE * 2 + 1) * (RENDER_DISTANCE * 2 + 1);
            if (failedChunks.size() > totalChunks * 0.25) {
                System.err.println("FATAL: Too many chunks failed to generate (" + failedChunks.size() + "/" + totalChunks + "). World loading has been compromised.");
                // Notify loading screen of critical failure
                Game game = Game.getInstance();
                if (game != null && game.getLoadingScreen() != null && game.getLoadingScreen().isVisible()) {
                    game.getLoadingScreen().reportError("Critical world generation failure - too many chunks failed");
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
     * Unloads a chunk at a specific position, cleaning up its resources.
     * This is now called by the ChunkLoader.
     */
    public void unloadChunk(int chunkX, int chunkZ) {
        ChunkPosition pos = getCachedChunkPosition(chunkX, chunkZ);
        Chunk chunk = chunks.remove(pos);

        if (chunk != null) {
            chunksToBuildMesh.remove(chunk);
            chunksReadyForGLUpload.remove(chunk);
            chunksFailedToBuildMesh.remove(chunk);
            chunkRetryCount.remove(chunk);

            chunk.cleanupCpuResources();
            chunksPendingGpuCleanup.offer(chunk);
            
            // Remove entities within this chunk
            Game.getEntityManager().removeEntitiesInChunk(chunkX, chunkZ);
            
            long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            chunkPositionCache.remove(key);
            
            // Log memory usage after unloading chunks (every 10th unload)
            if ((chunkX + chunkZ) % 10 == 0) {
                Game.logDetailedMemoryInfo("After unloading chunk (" + chunkX + ", " + chunkZ + ")");
            }
            
            // Optional: Log unloading
            // System.out.println("Unloaded chunk at (" + chunkX + ", " + chunkZ + ")");
        }
    }
      /**
     * Cleans up resources when the game exits.
     */
    public void cleanup() {
        // First, shut down the chunk manager's executor
        if (chunkManager != null) {
            chunkManager.shutdown();
        }

        // Then, shut down the chunk build executor
        chunkBuildExecutor.shutdown();
        try {
            if (!chunkBuildExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                System.err.println("Chunk build executor did not terminate in 2 seconds. Forcing shutdown...");
                chunkBuildExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            System.err.println("Error while shutting down chunk build executor: " + e.getMessage());
            chunkBuildExecutor.shutdownNow();
        }
        
        // Clear async processing queues
        chunksToBuildMesh.clear();
        chunksReadyForGLUpload.clear();
        chunksFailedToBuildMesh.clear();
        chunkRetryCount.clear();
        
        // Now clean up chunk resources after ensuring no threads are modifying the collection
        for (Chunk chunk : new HashMap<>(chunks).values()) {
            chunk.cleanupGpuResources();
        }
        
        // Process any remaining chunks in the cleanup queue
        processGpuCleanupQueue();

        // Finally clear the chunks map and cache
        chunks.clear();
        chunkPositionCache.clear();
    }
    
    /**
     * Returns the total number of loaded chunks.
     * This is used for debugging purposes.
     */
    public int getLoadedChunkCount() {
        return chunks.size();
    }
    
    /**
     * Returns the number of chunks pending mesh build.
     * This is used for debugging purposes.
     */
    public int getPendingMeshBuildCount() {
        return chunksToBuildMesh.size();
    }
    
    /**
     * Returns the number of chunks pending GL upload.
     * This is used for debugging purposes.
     */
    public int getPendingGLUploadCount() {
        return chunksReadyForGLUpload.size();
    }
    
    /**
     * Gets the snow layer manager for this world
     */
    public SnowLayerManager getSnowLayerManager() {
        return snowLayerManager;
    }
    
    /**
     * Gets the block drop manager for this world
     */
    public BlockDropManager getBlockDropManager() {
        return blockDropManager;
    }
    
    /**
     * Gets the chunk build executor for async operations.
     * @return The ExecutorService used for chunk mesh building
     */
    public java.util.concurrent.ExecutorService getChunkBuildExecutor() {
        return chunkBuildExecutor;
    }
    
    /**
     * Gets the snow layer count at a specific position
     */
    public int getSnowLayers(int x, int y, int z) {
        return snowLayerManager.getSnowLayers(x, y, z);
    }
    
    /**
     * Gets the visual/collision height of a snow block at a specific position
     */
    public float getSnowHeight(int x, int y, int z) {
        BlockType block = getBlockAt(x, y, z);
        if (block == BlockType.SNOW) {
            return snowLayerManager.getSnowHeight(x, y, z);
        }
        return block.getVisualHeight();
    }
    
    /**
     * Triggers a chunk mesh rebuild for the chunk containing the given world coordinates.
     * Use this when block visual properties change without changing the block type.
     */
    public void triggerChunkRebuild(int worldX, int worldY, int worldZ) {
        int chunkX = Math.floorDiv(worldX, CHUNK_SIZE);
        int chunkZ = Math.floorDiv(worldZ, CHUNK_SIZE);
        
        Chunk chunk = chunks.get(getCachedChunkPosition(chunkX, chunkZ));
        if (chunk != null) {
            synchronized (chunk) {
                chunk.setDataReadyForGL(false);
                chunk.setMeshDataGenerationScheduledOrInProgress(false);
            }
            conditionallyScheduleMeshBuild(chunk);
        }
    }
    
    
    private void forceUnloadDistantChunks(int maxUnloadCount) {
        Player player = Game.getPlayer();
        if (player == null) return;
        
        int playerChunkX = (int) Math.floor(player.getPosition().x / CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / CHUNK_SIZE);
        
        // More aggressive emergency distance based on severity
        int emergencyDistance = maxUnloadCount > 200 ? RENDER_DISTANCE + 3 : RENDER_DISTANCE + 1;
        
        Set<ChunkPosition> chunksToUnload = new HashSet<>();
        
        // Find chunks beyond emergency distance
        for (ChunkPosition pos : chunks.keySet()) {
            int distance = Math.max(Math.abs(pos.getX() - playerChunkX), Math.abs(pos.getZ() - playerChunkZ));
            if (distance > emergencyDistance) {
                chunksToUnload.add(pos);
            }
        }
        
        // Sort chunks by distance (unload furthest first for maximum memory recovery)
        java.util.List<ChunkPosition> sortedUnloads = new java.util.ArrayList<>(chunksToUnload);
        sortedUnloads.sort((a, b) -> {
            int distA = Math.max(Math.abs(a.getX() - playerChunkX), Math.abs(a.getZ() - playerChunkZ));
            int distB = Math.max(Math.abs(b.getX() - playerChunkX), Math.abs(b.getZ() - playerChunkZ));
            return Integer.compare(distB, distA); // Furthest first
        });
        
        // Unload distant chunks more aggressively
        int unloadedCount = 0;
        for (ChunkPosition pos : sortedUnloads) {
            unloadChunk(pos.getX(), pos.getZ());
            unloadedCount++;
            
            // Don't process GPU cleanup here - it must be done on main thread
            
            // Dynamic unloading limit based on severity
            if (unloadedCount >= maxUnloadCount) break;
        }
        
        if (unloadedCount > 0) {
            // Clear position cache aggressively during emergency
            if (chunkPositionCache.size() > 100) {
                chunkPositionCache.clear();
            }
            
            System.out.println("Emergency unloaded " + unloadedCount + " distant chunks. Total remaining: " + getLoadedChunkCount());
            Game.forceGCAndReport("After emergency chunk unloading");
        }
    }
    
    public void processGpuCleanupQueue() {
        Chunk chunk;
        int cleaned = 0;
        while ((chunk = chunksPendingGpuCleanup.poll()) != null) {
            chunk.cleanupGpuResources();
            cleaned++;
        }
        
        // Log when significant GPU cleanup occurs
        if (cleaned > 10) {
            System.out.println("Cleaned up GPU resources for " + cleaned + " chunks");
        }
    }
    
    /**
     * Clears world data for switching between worlds without shutting down thread pools.
     * This preserves the rendering system while clearing chunk data.
     */
    public void clearWorldData() {
        System.out.println("Clearing world data for world switching...");
        
        // Clear GPU resources for existing chunks before clearing the map
        for (Chunk chunk : chunks.values()) {
            if (chunk != null) {
                chunk.cleanupGpuResources();
            }
        }
        
        // Clear all chunk-related maps and queues
        chunks.clear();
        chunkPositionCache.clear();
        chunksToBuildMesh.clear();
        chunksReadyForGLUpload.clear();
        chunksFailedToBuildMesh.clear();
        chunkRetryCount.clear();
        chunksPendingGpuCleanup.clear();
        
        // Reset loading state
        this.isWorldLoading = true;
        this.worldLoadingStartTime = System.currentTimeMillis();
        
        // Reset any managers
        if (snowLayerManager != null) {
            snowLayerManager.clear();
        }
        if (blockDropManager != null) {
            blockDropManager.clearAllDrops();
        }
        
        System.out.println("World data cleared successfully");
    }
    
    /**
     * Completely resets the world state for switching between different worlds.
     * This clears all chunks, caches, and reinitializes with a new seed.
     */
    public void reset(long newSeed) {
        System.out.println("Resetting world state with new seed: " + newSeed);
        
        // Clear world data without shutting down threads
        clearWorldData();
        
        // Reset world state with new seed
        this.seed = newSeed;
        this.deterministicRandom = new DeterministicRandom(newSeed);
        this.terrainNoise = new NoiseGenerator(newSeed);
        this.temperatureNoise = new NoiseGenerator(newSeed + 1);
        this.continentalnessNoise = new NoiseGenerator(newSeed + 2);
        
        System.out.println("World state reset completed with seed: " + newSeed);
    }
    
    /**
     * Sets the seed for world generation and reinitializes the noise generators.
     * This will affect future chunk generation but won't regenerate existing chunks.
     * Ensures all deterministic systems use consistent seeds.
     */
    public void setSeed(long seed) {
        System.out.println("Updating world seed from " + this.seed + " to " + seed);
        
        this.seed = seed;
        this.deterministicRandom = new DeterministicRandom(seed);
        this.terrainNoise = new NoiseGenerator(seed);
        this.temperatureNoise = new NoiseGenerator(seed + 1); // Offset for temperature variation
        this.continentalnessNoise = new NoiseGenerator(seed + 2); // Offset for continental variation
        
        // Validate seed propagation
        validateSeedConsistency();
        
        System.out.println("World seed successfully updated to: " + seed + " (deterministic generation enabled)");
    }
    
    /**
     * Validates that all deterministic generation systems use consistent seeds.
     * This ensures reproducible world generation.
     */
    private void validateSeedConsistency() {
        // Verify all noise generators are initialized
        if (terrainNoise == null || temperatureNoise == null || continentalnessNoise == null) {
            System.err.println("WARNING: Some noise generators are null after seed update");
            return;
        }
        
        if (deterministicRandom == null) {
            System.err.println("WARNING: DeterministicRandom is null after seed update");
            return;
        }
        
        // Log seed configuration for debugging
        System.out.println("Seed consistency validated:");
        System.out.println("  Main seed: " + seed);
        System.out.println("  Terrain noise seed: " + seed);
        System.out.println("  Temperature noise seed: " + (seed + 1));
        System.out.println("  Continentalness noise seed: " + (seed + 2));
        System.out.println("  DeterministicRandom initialized: " + (deterministicRandom != null));
    }
    
    /**
     * Gets the current world seed.
     */
    public long getSeed() {
        return seed;
    }
    
    /**
     * Sets a chunk at the specified coordinates.
     * Used by WorldLoader to register loaded chunks.
     */
    public void setChunk(int chunkX, int chunkZ, Chunk chunk) {
        if (chunk == null) {
            System.err.println("WARNING: Attempted to set null chunk at (" + chunkX + ", " + chunkZ + ")");
            return;
        }
        
        ChunkPosition position = getCachedChunkPosition(chunkX, chunkZ);
        chunks.put(position, chunk);
        
        // Schedule mesh build for the loaded chunk
        conditionallyScheduleMeshBuild(chunk);
        
        System.out.println("Registered loaded chunk at (" + chunkX + ", " + chunkZ + ")");
    }
    
    /**
     * Sets the spawn position for this world.
     * Stores the spawn position for save/load operations.
     */
    public void setSpawnPosition(org.joml.Vector3f spawnPosition) {
        if (spawnPosition != null) {
            this.spawnPosition = new org.joml.Vector3f(spawnPosition); // Make a copy to avoid external modifications
            System.out.println("World spawn position set to: " + spawnPosition);
        }
    }
    
    /**
     * Gets the current spawn position for this world.
     * @return The spawn position as a Vector3f, or null if not set
     */
    public org.joml.Vector3f getSpawnPosition() {
        if (spawnPosition != null) {
            return new org.joml.Vector3f(spawnPosition); // Return a copy to prevent external modifications
        }
        return null;
    }
    
    /**
     * Marks the world as finished loading to switch to runtime memory management.
     */
    public void setWorldLoadingComplete() {
        this.isWorldLoading = false;
        long loadingTime = System.currentTimeMillis() - worldLoadingStartTime;
        System.out.println("World loading completed in " + loadingTime + "ms. Switching to runtime memory management.");
    }
    
    /**
     * Checks if the world is currently in the loading phase.
     */
    public boolean isWorldLoading() {
        return isWorldLoading;
    }
    
    /**
     * Gets the time when world loading started.
     */
    public long getWorldLoadingStartTime() {
        return worldLoadingStartTime;
    }
    
    /**
     * Gets all currently loaded chunks. Used by WorldSaver for selective saving.
     * @return Collection of all loaded chunks
     */
    public java.util.Collection<Chunk> getAllLoadedChunks() {
        return chunks.values();
    }
    
    /**
     * Validates deterministic generation by testing reproducibility at a sample location.
     * This can be used to ensure that world generation produces consistent results.
     */
    public boolean validateDeterministicGeneration(int testX, int testZ) {
        if (deterministicRandom == null) {
            System.err.println("Cannot validate deterministic generation: DeterministicRandom is null");
            return false;
        }
        
        // Test terrain height generation
        int height1 = generateTerrainHeight(testX, testZ);
        int height2 = generateTerrainHeight(testX, testZ);
        
        if (height1 != height2) {
            System.err.println("DETERMINISTIC VALIDATION FAILED: Terrain height inconsistent at (" + 
                             testX + ", " + testZ + ") - got " + height1 + " and " + height2);
            return false;
        }
        
        // Test biome generation
        BiomeType biome1 = getBiomeType(testX, testZ);
        BiomeType biome2 = getBiomeType(testX, testZ);
        
        if (biome1 != biome2) {
            System.err.println("DETERMINISTIC VALIDATION FAILED: Biome inconsistent at (" + 
                             testX + ", " + testZ + ") - got " + biome1 + " and " + biome2);
            return false;
        }
        
        // Test deterministic random feature generation
        boolean feature1 = deterministicRandom.shouldGenerate(testX, testZ, "validation_test", 0.5f);
        boolean feature2 = deterministicRandom.shouldGenerate(testX, testZ, "validation_test", 0.5f);
        
        if (feature1 != feature2) {
            System.err.println("DETERMINISTIC VALIDATION FAILED: Feature generation inconsistent at (" + 
                             testX + ", " + testZ + ") - got " + feature1 + " and " + feature2);
            return false;
        }
        
        System.out.println("Deterministic generation validation passed at (" + testX + ", " + testZ + ")");
        return true;
    }
    
    /**
     * Logs detailed information about the current deterministic generation state.
     * Useful for debugging world generation issues.
     */
    public void logGenerationState() {
        System.out.println("=== World Deterministic Generation State ===");
        System.out.println("World seed: " + seed);
        System.out.println("Loaded chunks: " + chunks.size());
        System.out.println("Noise generators initialized: " + 
                          (terrainNoise != null) + "/" + 
                          (temperatureNoise != null) + "/" + 
                          (continentalnessNoise != null));
        System.out.println("DeterministicRandom initialized: " + (deterministicRandom != null));
        System.out.println("World loading state: " + (isWorldLoading ? "LOADING" : "RUNTIME"));
        System.out.println("============================================");
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
