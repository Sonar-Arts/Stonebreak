package com.stonebreak;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
// import java.util.List; // Unused
// import java.util.ArrayList; // Unused
import java.util.Queue;
import java.util.Set;
import java.util.HashSet;
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
    private long seed;
    private Random random;
    private NoiseGenerator terrainNoise;
    
    // Stores all chunks in the world
    private Map<ChunkPosition, Chunk> chunks;
    
    // Tracks which chunks need mesh updates
    private ExecutorService chunkBuildExecutor;
    private Set<Chunk> chunksToBuildMesh; // Chunks needing their mesh data (re)built
    private Queue<Chunk> chunksReadyForGLUpload; // Chunks with mesh data ready for GL upload
    
    // Chunk unloading timer
    private float chunkUnloadCounter = 0;
    private float chunkRenderTickCounter = 0; // Timer for render tick
    private static final float CHUNK_RENDER_TICK_INTERVAL = 0.5f; // Check every 0.5 seconds

    public World() {
        this.seed = System.currentTimeMillis();
        this.random = new Random(seed);
        this.terrainNoise = new NoiseGenerator(seed);
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

                if (chunk != null) { // Chunk exists
                    // Check if it's NOT in a "perfectly fine" state (i.e. mesh generated AND data ready for GL)
                    boolean isPerfectlyFine = chunk.isMeshGenerated() && chunk.isDataReadyForGL();
                    
                    if (!isPerfectlyFine) {
                        // System.out.println("EnsureVisible: Chunk (" + cx + "," + cz + ") is not perfect. Resetting and scheduling.");
                        // Forcefully reset flags for the primary chunk
                        synchronized (chunk) {
                            chunk.setDataReadyForGL(false);
                            chunk.setMeshDataGenerationScheduledOrInProgress(false);
                        }
                        conditionallyScheduleMeshBuild(chunk);

                        // Also reset flags and schedule direct neighbors, similar to setBlockAt
                        int[][] neighborOffsets = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
                        for (int[] offset : neighborOffsets) {
                            ChunkPosition neighborPos = new ChunkPosition(cx + offset[0], cz + offset[1]);
                            Chunk neighbor = chunks.get(neighborPos);
                            if (neighbor != null) {
                                // Check if neighbor also needs a refresh based on its own state
                                boolean neighborIsPerfect = neighbor.isMeshGenerated() && neighbor.isDataReadyForGL();
                                if (!neighborIsPerfect) {
                                     // System.out.println("EnsureVisible: Resetting flags for neighbor (" + neighborPos.getX() + "," + neighborPos.getZ() + ") of chunk (" + cx + "," + cz + ")");
                                    synchronized (neighbor) {
                                        neighbor.setDataReadyForGL(false);
                                        neighbor.setMeshDataGenerationScheduledOrInProgress(false);
                                    }
                                    conditionallyScheduleMeshBuild(neighbor);
                                }
                            }
                        }
                    } else {
                        // Chunk is perfectly fine, but still call conditionallyScheduleMeshBuild
                        // in case it was perfect but somehow dropped from queues (highly unlikely).
                        // The internal checks in conditionallyScheduleMeshBuild will prevent redundant processing.
                        conditionallyScheduleMeshBuild(chunk);
                    }
                } else { // Chunk doesn't exist in the map
                    getChunkAt(cx, cz);
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
     */
    public void setBlockAt(int x, int y, int z, BlockType blockType) {
        if (y < 0 || y >= WORLD_HEIGHT) {
            return;
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
    }
    
    /**
     * Generates a new chunk at the specified position.
     */
    private Chunk generateChunk(int chunkX, int chunkZ) {
        Chunk chunk = new Chunk(chunkX, chunkZ);
        
        // Generate terrain
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                // Calculate absolute world coordinates
                int worldX = chunkX * CHUNK_SIZE + x;
                int worldZ = chunkZ * CHUNK_SIZE + z;
                
                // Generate height map using noise
                int height = generateTerrainHeight(worldX, worldZ);
                
                // Generate blocks based on height
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    BlockType blockType;
                    
                    if (y == 0) {
                        blockType = BlockType.BEDROCK;
                    } else if (y < height - 4) {
                        blockType = BlockType.STONE;
                        
                        // Generate some ore veins (synchronized access to random)
                        BlockType oreType = null;
                        synchronized (this.random) {
                            if (this.random.nextFloat() < 0.01) {
                                oreType = BlockType.COAL_ORE;
                            } else if (this.random.nextFloat() < 0.005 && y < 40) {
                                oreType = BlockType.IRON_ORE;
                            }
                        }
                        if (oreType != null) {
                            blockType = oreType;
                        }
                    } else if (y < height - 1) {
                        blockType = BlockType.DIRT;
                    } else if (y < height) {
                        // Top layer
                        float moisture = generateMoisture(worldX, worldZ);
                        if (moisture < 0.3) {
                            blockType = BlockType.SAND;
                        } else {
                            blockType = BlockType.GRASS;
                        }                    } else if (y < 64) {
                        // Water level - fill all areas below y=64 with water (unless above terrain)
                        blockType = BlockType.WATER;
                    } else {
                        blockType = BlockType.AIR;
                    }
                    
                    chunk.setBlock(x, y, z, blockType);
                }
                
                // Generate trees (synchronized access to random)
                boolean shouldGenerateTree;
                synchronized (this.random) {
                    shouldGenerateTree = (this.random.nextFloat() < 0.01 && height > 64);
                }
                if (shouldGenerateTree) {
                    generateTree(chunk, x, height, z);
                }
            }
        }
        
        return chunk;
    }
    
    /**
     * Generates a tree at the specified position.
     */
    private void generateTree(Chunk chunk, int x, int y, int z) {
        // Check if we have enough space for the tree
        if (y + 6 >= WORLD_HEIGHT || x < 2 || x >= CHUNK_SIZE - 2 || z < 2 || z >= CHUNK_SIZE - 2) {
            return;
        }
        
        // Place tree trunk
        for (int dy = 0; dy < 5; dy++) {
            chunk.setBlock(x, y + dy, z, BlockType.WOOD);
        }
        
        // Place leaves
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 3; dy <= 6; dy++) {
                    // Skip the corners and center
                    if ((Math.abs(dx) == 2 && Math.abs(dz) == 2) || (dx == 0 && dz == 0 && dy < 5)) {
                        continue;
                    }
                    
                    // Skip if outside chunk bounds
                    if (x + dx < 0 || x + dx >= CHUNK_SIZE || z + dz < 0 || z + dz >= CHUNK_SIZE) {
                        continue;
                    }
                    
                    chunk.setBlock(x + dx, y + dy, z + dz, BlockType.LEAVES);
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
            Chunk newGeneratedChunk = generateChunk(chunkX, chunkZ);
            
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
            e.printStackTrace();
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
        
        float heightValue = 0.0f;
        
        // Base terrain
        float baseHeight = terrainNoise.noise(nx, nz) * 0.5f + 0.5f;
        
        // Hills
        float hillsValue = terrainNoise.noise(nx * 2, nz * 2) * 0.25f;
        
        // Mountains
        float mountainValue = terrainNoise.noise(nx * 0.5f, nz * 0.5f);
        mountainValue = mountainValue * mountainValue * 0.3f;
        
        // Combine noise layers
        heightValue = baseHeight + hillsValue + mountainValue;
        
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
        
        return terrainNoise.noise(nx + 100, nz + 100) * 0.5f + 0.5f;
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
                    e.printStackTrace();
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
        for (int x = playerChunkX - RENDER_DISTANCE; x <= playerChunkX + RENDER_DISTANCE; x++) {
            for (int z = playerChunkZ - RENDER_DISTANCE; z <= playerChunkZ + RENDER_DISTANCE; z++) {
                ChunkPosition position = new ChunkPosition(x, z);
                if (!chunks.containsKey(position)) {
                    // Generate new chunk safely
                    safelyGenerateAndRegisterChunk(x, z);
                }
            }
        }
        
        // Second pass: Add one extra chunk in each direction to ensure edge chunks have valid neighbors
        // This fixes the "holes" in rendering by ensuring all visible chunk borders have neighbors
        for (int x = playerChunkX - RENDER_DISTANCE - 1; x <= playerChunkX + RENDER_DISTANCE + 1; x++) {
            for (int z = playerChunkZ - RENDER_DISTANCE - 1; z <= playerChunkZ + RENDER_DISTANCE + 1; z++) {
                // Only process edge chunks
                if ((x == playerChunkX - RENDER_DISTANCE - 1 || x == playerChunkX + RENDER_DISTANCE + 1 ||
                     z == playerChunkZ - RENDER_DISTANCE - 1 || z == playerChunkZ + RENDER_DISTANCE + 1)) {
                    
                    ChunkPosition position = new ChunkPosition(x, z);
                    if (!chunks.containsKey(position)) {
                        // Generate new chunk safely
                        safelyGenerateAndRegisterChunk(x, z);
                    }
                }
            }
        }
        
        // Add only visible chunks to the return map
        for (int x = playerChunkX - RENDER_DISTANCE; x <= playerChunkX + RENDER_DISTANCE; x++) {
            for (int z = playerChunkZ - RENDER_DISTANCE; z <= playerChunkZ + RENDER_DISTANCE; z++) {
                ChunkPosition position = new ChunkPosition(x, z);
                visibleChunks.put(position, chunks.get(position));
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
