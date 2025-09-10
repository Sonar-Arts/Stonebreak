package com.stonebreak.world;

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
    private final long seed;
    private final Random random;
    private final NoiseGenerator terrainNoise;
    private final NoiseGenerator temperatureNoise; // For biome determination
    private final NoiseGenerator continentalnessNoise;
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

    public World() {
        this.seed = System.currentTimeMillis();
        this.random = new Random(seed);
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

        // CRITICAL: Proactive memory management with multiple thresholds
        int loadedChunks = getLoadedChunkCount();
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

        // Process requests to build mesh data (async)
        processChunkMeshBuildRequests();

        // Apply mesh data to GL objects on the main thread
        
        // Process GPU resource cleanups on the main thread
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
     */
    public Chunk getChunkAt(int x, int z) {
        ChunkPosition position = getCachedChunkPosition(x, z);
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
                
                // Generate Trees (in PLAINS and SNOWY_PLAINS biomes)
                if (biome == BiomeType.PLAINS) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.GRASS) { // Ensure tree spawns on grass
                        float treeChance;
                        synchronized (randomLock) {
                            treeChance = this.random.nextFloat();
                        }
                        
                        if (treeChance < 0.01 && surfaceHeight > 64) { // 1% total tree chance
                            // Determine tree type: 60% regular oak, 40% elm trees
                            boolean shouldGenerateElm;
                            synchronized (randomLock) {
                                shouldGenerateElm = this.random.nextFloat() < 0.4f; // 40% chance for elm
                            }
                            
                            if (shouldGenerateElm) {
                                generateElmTree(chunk, x, surfaceHeight, z);
                            } else {
                                generateTree(chunk, x, surfaceHeight, z);
                            }
                        }
                    }
                } else if (biome == BiomeType.SNOWY_PLAINS) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SNOWY_DIRT) { // Pine trees spawn on snowy dirt
                        boolean shouldGeneratePineTree;
                        synchronized (randomLock) {
                            shouldGeneratePineTree = (this.random.nextFloat() < 0.015 && surfaceHeight > 64); // Slightly higher chance for pine trees
                        }
                        if (shouldGeneratePineTree) {
                            generatePineTree(chunk, x, surfaceHeight, z);
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
                
                // Generate Gravel patches on sand surfaces in DESERT biome
                if (biome == BiomeType.DESERT && surfaceHeight > 64 && surfaceHeight < WORLD_HEIGHT) {
                    if (chunk.getBlock(x, surfaceHeight - 1, z) == BlockType.SAND) {
                        boolean shouldGenerateGravel;
                        synchronized (randomLock) {
                            shouldGenerateGravel = this.random.nextFloat() < 0.01; // 1% chance for gravel patches
                        }
                        if (shouldGenerateGravel) {
                            // Create small gravel patches (2x2 or 3x3)
                            int patchSize;
                            synchronized (randomLock) {
                                patchSize = this.random.nextBoolean() ? 2 : 3; // Random patch size
                            }
                            
                            for (int dx = 0; dx < patchSize; dx++) {
                                for (int dz = 0; dz < patchSize; dz++) {
                                    int patchWorldX = worldX + dx - patchSize/2;
                                    int patchWorldZ = worldZ + dz - patchSize/2;
                                    int patchY = this.generateTerrainHeight(patchWorldX, patchWorldZ) - 1;
                                    
                                    // Only place gravel if the spot is sand
                                    if (this.getBlockAt(patchWorldX, patchY, patchWorldZ) == BlockType.SAND) {
                                        this.setBlockAt(patchWorldX, patchY, patchWorldZ, BlockType.GRAVEL);
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
                        float featureChance;
                        synchronized (randomLock) {
                            featureChance = this.random.nextFloat();
                        }
                        
                        if (featureChance < 0.03) { // 3% chance for ice patches
                            this.setBlockAt(worldX, surfaceHeight, worldZ, BlockType.ICE);
                        } else if (featureChance < 0.08) { // Additional 5% chance for snow layers
                            this.setBlockAt(worldX, surfaceHeight, worldZ, BlockType.SNOW);
                            // Set initial snow layer count (1-3 layers randomly)
                            int layers;
                            synchronized (randomLock) {
                                layers = 1 + this.random.nextInt(3); // 1, 2, or 3 layers
                            }
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
            float cowSpawnChance;
            synchronized (randomLock) {
                cowSpawnChance = this.random.nextFloat();
            }
            
            if (cowSpawnChance < 0.1f) { // 10% chance per chunk
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
        // Randomly vary height based on research: elms can be quite tall
        int trunkHeight;
        synchronized (randomLock) {
            trunkHeight = 8 + this.random.nextInt(5); // 8-12 blocks tall trunk
        }
        
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
                        synchronized (randomLock) {
                            shouldPlaceLeaf = this.random.nextFloat() > 0.3f; // 70% chance for outer leaves
                        }
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
                    boolean shouldPlaceLeaf;
                    synchronized (randomLock) {
                        shouldPlaceLeaf = this.random.nextFloat() > 0.2f; // 80% chance
                    }
                    
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
        
        // Determine number of cows to spawn (1-4)
        int cowCount;
        synchronized (randomLock) {
            cowCount = 1 + this.random.nextInt(4); // 1, 2, 3, or 4 cows
        }
        
        int spawned = 0;
        int attempts = 0;
        int maxAttempts = 20; // Limit attempts to prevent infinite loops
        
        while (spawned < cowCount && attempts < maxAttempts) {
            attempts++;
            
            // Random position within chunk
            int localX, localZ;
            synchronized (randomLock) {
                localX = this.random.nextInt(CHUNK_SIZE);
                localZ = this.random.nextInt(CHUNK_SIZE);
            }
            
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
                
                // Select random texture variant for world generation cow spawning
                String[] variants = {"default", "angus", "highland"};
                String textureVariant = variants[(int)(Math.random() * variants.length)];
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
     */
    private Chunk safelyGenerateAndRegisterChunk(int chunkX, int chunkZ) {
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
     */
    public Map<ChunkPosition, Chunk> getChunksAroundPlayer(int playerChunkX, int playerChunkZ) {
        Map<ChunkPosition, Chunk> visibleChunks = new HashMap<>();
        
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
