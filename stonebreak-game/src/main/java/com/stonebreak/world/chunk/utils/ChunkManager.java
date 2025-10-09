package com.stonebreak.world.chunk.utils;

import com.stonebreak.world.chunk.ChunkStatus;
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;
import com.stonebreak.world.chunk.api.commonChunkOperations.state.CcoAtomicStateManager;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * CCO-optimized chunk manager for efficient chunk lifecycle management.
 * Uses Common Chunk Operations API for atomic state tracking and coordination.
 *
 * Key improvements:
 * - CCO atomic state management for thread-safe operations
 * - Unified dirty tracking through CCO API
 * - Neighbor coordination via CcoNeighborCoordinator (CCO API)
 * - Lock-free state queries and transitions
 */
public class ChunkManager {

    private final World world;
    private final int renderDistance;
    private final WorldConfiguration config;
    private final Set<ChunkPosition> activeChunkPositions = Collections.synchronizedSet(new HashSet<>());
    private float updateTimer = 0.0f;

    // Use configurable thread pool size (4-6 threads typical)
    // Dynamically calculated based on CPU cores, min 2, max 8
    private final ExecutorService chunkExecutor;
    private static final float UPDATE_INTERVAL = 1.0f; // Update every second
    private static volatile boolean optimizationsEnabled = true;
    private static long lastMemoryCheck = 0;
    private static boolean highMemoryPressure = false;

    // Simple configuration
    private static final long MEMORY_CHECK_INTERVAL = 2000; // 2 seconds
    private static final double HIGH_MEMORY_THRESHOLD = 0.8; // 80% memory usage

    public ChunkManager(World world, int renderDistance) {
        this.world = world;
        this.renderDistance = renderDistance;
        this.config = new WorldConfiguration(renderDistance, calculateOptimalChunkLoadThreads());

        // Create thread pool with optimal thread count
        // Typically 4-6 threads on modern CPUs
        int threadCount = this.config.getChunkBuildThreads();
        this.chunkExecutor = Executors.newFixedThreadPool(threadCount,
            new java.util.concurrent.ThreadFactory() {
                private int threadId = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "ChunkLoader-" + threadId++);
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1); // Slightly lower priority than main thread
                    return thread;
                }
            });

        System.out.println("ChunkManager: Using " + threadCount + " chunk loading threads");
    }

    /**
     * Calculates optimal thread count for chunk loading operations.
     * Uses more threads than mesh building since loading is I/O bound.
     */
    private static int calculateOptimalChunkLoadThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        // Min 2, max 8, typically cores/2 + 1
        return Math.max(2, Math.min(8, cores / 2 + 1));
    }

    public void update(Player player) {
        updateTimer += Game.getDeltaTime();
        if (updateTimer >= UPDATE_INTERVAL) {
            updateActiveChunks(player);
            updateTimer = 0;
        }
    }

    private void updateActiveChunks(Player player) {
        if (player == null) return;

        int playerChunkX = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);

        Set<ChunkPosition> requiredChunks = calculateRequiredChunks(playerChunkX, playerChunkZ);
        unloadExitedChunks(requiredChunks);
        loadEnteredChunks(requiredChunks);
        ensureVisibleChunksAreReady(playerChunkX, playerChunkZ);
    }

    private Set<ChunkPosition> calculateRequiredChunks(int playerChunkX, int playerChunkZ) {
        Set<ChunkPosition> required = new HashSet<>();
        int loadDistance = renderDistance + 1;
        for (int x = playerChunkX - loadDistance; x <= playerChunkX + loadDistance; x++) {
            for (int z = playerChunkZ - loadDistance; z <= playerChunkZ + loadDistance; z++) {
                required.add(world.getCachedChunkPosition(x, z));
            }
        }
        return required;
    }

    private void unloadExitedChunks(Set<ChunkPosition> requiredChunks) {
        Set<ChunkPosition> chunksToUnload = new HashSet<>(activeChunkPositions);
        chunksToUnload.removeAll(requiredChunks);

        if (!chunksToUnload.isEmpty()) {
            int totalCandidates = chunksToUnload.size();
            int protectedCount = 0;
            int unloadedCount = 0;

            // Logging removed for performance - chunk unloading happens frequently

            // OPTIMIZATION: Batch clean chunks for single executor submission
            List<ChunkPosition> cleanChunksToUnload = new ArrayList<>();

            for (ChunkPosition pos : chunksToUnload) {
                // Use CCO API to check if chunk exists and needs saving
                if (world.hasChunkAt(pos.getX(), pos.getZ())) {
                    Chunk chunk = world.getChunkAt(pos.getX(), pos.getZ());
                    if (chunk != null) {
                        CcoAtomicStateManager stateManager = chunk.getCcoStateManager();

                        // Check if chunk is already being unloaded
                        if (stateManager.hasState(CcoChunkState.UNLOADING)) {
                            continue; // Skip chunks already unloading
                        }

                        // Use CCO dirty tracker to check if save is needed
                        if (stateManager.needsSave()) {
                            // SAVE-THEN-UNLOAD: Save dirty chunk first, then unload it
                            saveAndUnloadDirtyChunk(pos, chunk);
                            protectedCount++; // Count as "protected" but actually handled
                            continue; // Skip normal unload since we handled it specially
                        }
                    }
                }

                // Chunk is clean or doesn't exist, safe to batch unload
                cleanChunksToUnload.add(pos);
                // Remove from active chunks BEFORE attempting unload to prevent double-unload
                activeChunkPositions.remove(pos);
            }

            // OPTIMIZATION: Batch unload clean chunks in single executor task
            if (!cleanChunksToUnload.isEmpty()) {
                final int batchSize = cleanChunksToUnload.size();
                chunkExecutor.submit(() -> {
                    for (ChunkPosition pos : cleanChunksToUnload) {
                        try {
                            world.unloadChunk(pos.getX(), pos.getZ());
                        } catch (Exception e) {
                            // Extract meaningful error message
                            String errorMsg = e.getMessage();
                            if (errorMsg == null && e.getCause() != null) {
                                errorMsg = e.getCause().getMessage();
                            }
                            if (errorMsg == null || errorMsg.isEmpty()) {
                                errorMsg = e.getClass().getSimpleName();
                                if (e.getCause() != null) {
                                    errorMsg += " (caused by " + e.getCause().getClass().getSimpleName() + ")";
                                }
                            }

                            System.err.println("ChunkManager: Failed to unload chunk (" + pos.getX() + ", " + pos.getZ() + "): " + errorMsg);
                            if (e.getCause() != null && e.getCause().getMessage() != null) {
                                System.err.println("  Caused by: " + e.getCause().getMessage());
                            }

                            // Re-add to active chunks if unload failed
                            activeChunkPositions.add(pos);
                        }
                    }
                });
                unloadedCount = batchSize;
            }

            // Logging removed for performance - chunk unloading happens frequently
        }
    }

    /**
     * Saves a dirty chunk synchronously, then unloads it.
     * Uses CCO state management to ensure proper lifecycle transitions.
     * This ensures player edits are preserved while allowing normal chunk lifecycle.
     */
    private void saveAndUnloadDirtyChunk(ChunkPosition pos, Chunk chunk) {
        try {
            CcoAtomicStateManager stateManager = chunk.getCcoStateManager();

            // Mark chunk as unloading using CCO state transition
            if (!stateManager.addState(CcoChunkState.UNLOADING)) {
                System.err.println("ChunkManager: Failed to transition chunk to UNLOADING state: (" + pos.getX() + ", " + pos.getZ() + ")");
                return; // Can't unload if state transition failed
            }

            // Remove from active chunks first to prevent double-processing
            activeChunkPositions.remove(pos);

            // Submit save-then-unload operation
            chunkExecutor.submit(() -> {
                try {
                    // The WorldChunkStore.unloadChunk() method already handles saving dirty chunks
                    // via saveChunkOnUnload() before proceeding with unload
                    world.unloadChunk(pos.getX(), pos.getZ());
                    // Logging removed for performance - saves happen frequently
                } catch (Exception e) {
                    // Extract meaningful error message
                    String errorMsg = e.getMessage();
                    if (errorMsg == null && e.getCause() != null) {
                        errorMsg = e.getCause().getMessage();
                    }
                    if (errorMsg == null || errorMsg.isEmpty()) {
                        errorMsg = e.getClass().getSimpleName();
                        if (e.getCause() != null) {
                            errorMsg += " (caused by " + e.getCause().getClass().getSimpleName() + ")";
                        }
                    }

                    System.err.println("ChunkManager: CRITICAL: Failed to save-then-unload dirty chunk (" + pos.getX() + ", " + pos.getZ() + "): " + errorMsg);
                    if (e.getCause() != null && e.getCause().getMessage() != null) {
                        System.err.println("  Caused by: " + e.getCause().getMessage());
                    }
                    System.err.println("Full stack trace:");
                    e.printStackTrace();

                    // Re-add to active chunks if save-then-unload failed
                    activeChunkPositions.add(pos);

                    // Remove UNLOADING state since we failed
                    stateManager.removeState(CcoChunkState.UNLOADING);
                }
            });

        } catch (Exception e) {
            System.err.println("ChunkManager: Error initiating save-then-unload for chunk (" + pos.getX() + ", " + pos.getZ() + "): " + e.getMessage());
            // Keep chunk in active chunks if we couldn't even start the process
        }
    }

    private void loadEnteredChunks(Set<ChunkPosition> requiredChunks) {
        Set<ChunkPosition> chunksToLoad = new HashSet<>(requiredChunks);
        chunksToLoad.removeAll(activeChunkPositions);

        if (!chunksToLoad.isEmpty()) {
            // Get player position for distance calculation
            Player player = Game.getPlayer();
            int playerChunkX = player != null ? (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE) : 0;
            int playerChunkZ = player != null ? (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE) : 0;

            // Sort chunks by distance to player (closest first)
            List<ChunkPosition> sortedChunks = new ArrayList<>(chunksToLoad);
            sortedChunks.sort((a, b) -> {
                int distA = Math.max(Math.abs(a.getX() - playerChunkX), Math.abs(a.getZ() - playerChunkZ));
                int distB = Math.max(Math.abs(b.getX() - playerChunkX), Math.abs(b.getZ() - playerChunkZ));
                return Integer.compare(distA, distB);
            });

            // Load chunks in priority order (closest first)
            for (ChunkPosition pos : sortedChunks) {
                if (activeChunkPositions.add(pos)) {
                    // Calculate priority based on distance
                    int distance = Math.max(Math.abs(pos.getX() - playerChunkX), Math.abs(pos.getZ() - playerChunkZ));

                    chunkExecutor.submit(() -> {
                        try {
                            long startTime = System.currentTimeMillis();
                            Chunk chunk = world.getChunkAt(pos.getX(), pos.getZ());
                            long endTime = System.currentTimeMillis();

                            // Log slow chunk loading (only for nearby chunks)
                            if (endTime - startTime > 500 && distance <= 3) { // More than 500ms for nearby chunks
                                System.err.println("SLOW CHUNK LOAD: Chunk (" + pos.getX() + ", " + pos.getZ() + ") distance=" + distance + " took " + (endTime - startTime) + "ms");
                                System.err.println("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB used");
                            }

                            // Verify chunk was loaded properly using CCO states
                            if (chunk != null) {
                                CcoAtomicStateManager stateManager = chunk.getCcoStateManager();

                                if (!stateManager.hasAnyState(CcoChunkState.BLOCKS_POPULATED, CcoChunkState.FEATURES_POPULATED)) {
                                    System.err.println("WARNING: Chunk (" + pos.getX() + ", " + pos.getZ() + ") loaded but not populated. States: " + stateManager.getCurrentStates());
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("CRITICAL: Exception loading chunk (" + pos.getX() + ", " + pos.getZ() + ")");
                            System.err.println("Time: " + java.time.LocalDateTime.now());
                            System.err.println("Thread: " + Thread.currentThread().getName());
                            System.err.println("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB used");
                            System.err.println("Exception: " + e.getMessage());
                            System.err.println("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
                        }
                    });
                }
            }
        }
    }

    private void ensureVisibleChunksAreReady(int playerChunkX, int playerChunkZ) {
        for (int x = playerChunkX - renderDistance; x <= playerChunkX + renderDistance; x++) {
            for (int z = playerChunkZ - renderDistance; z <= playerChunkZ + renderDistance; z++) {
                world.ensureChunkIsReadyForRender(x, z);

                // PERIODIC VALIDATION: Double-check that chunks within close range have meshes
                // Uses CCO state management for accurate state queries
                int distance = Math.max(Math.abs(x - playerChunkX), Math.abs(z - playerChunkZ));
                if (distance <= 3) { // Only validate chunks very close to player
                    Chunk chunk = world.getChunkAt(x, z);
                    if (chunk != null) {
                        CcoAtomicStateManager stateManager = chunk.getCcoStateManager();

                        // Check if chunk is populated but mesh not ready
                        boolean isPopulated = stateManager.hasState(CcoChunkState.FEATURES_POPULATED);
                        boolean isMeshReady = stateManager.isRenderable();
                        boolean isMeshGenerating = stateManager.hasState(CcoChunkState.MESH_GENERATING);

                        if (isPopulated && !isMeshReady && !isMeshGenerating) {
                            // Found an invisible chunk - force immediate retry
                            System.out.println("CCO-VALIDATION: Chunk (" + x + ", " + z + ") populated but not renderable. Scheduling mesh generation.");
                            world.ensureChunkIsReadyForRender(x, z);
                        }
                    }
                }
            }
        }
    }

    // Adaptive GL batch sizing state
    private static int currentGLBatchSize = 16; // Start at safe default
    private static final int MIN_GL_BATCH_SIZE = 2;
    private static final int MAX_GL_BATCH_SIZE = 64;
    private static final float GL_TARGET_FRAME_TIME_MS = 16.0f; // 60 FPS target
    private static final float GL_HIGH_FRAME_TIME_MS = 18.0f; // Reduce uploads if above
    private static final float GL_LOW_FRAME_TIME_MS = 14.0f; // Increase uploads if below

    public static int getOptimizedGLBatchSize() {
        if (!optimizationsEnabled) {
            return 32;
        }

        updateMemoryPressure();

        // Get current frame time
        float deltaTimeMs = Game.getDeltaTime() * 1000.0f;

        // Adaptive adjustment based on frame time
        if (deltaTimeMs > GL_HIGH_FRAME_TIME_MS) {
            // Frame time too high - reduce GL uploads per frame
            currentGLBatchSize = Math.max(MIN_GL_BATCH_SIZE, currentGLBatchSize - 2);
        } else if (deltaTimeMs < GL_LOW_FRAME_TIME_MS) {
            // Frame time good - can increase GL uploads
            currentGLBatchSize = Math.min(MAX_GL_BATCH_SIZE, currentGLBatchSize + 1);
        }

        // Apply memory pressure limits
        if (highMemoryPressure) {
            // Under memory pressure, cap batch size
            currentGLBatchSize = Math.min(currentGLBatchSize, 8);
        }

        // Get memory-based base size for safety limits
        int memorySafeBatchSize = getMemorySafeBatchSize();

        // Return the more conservative of adaptive vs memory-safe
        return Math.min(currentGLBatchSize, memorySafeBatchSize);
    }

    /**
     * Gets a safe batch size based on current memory pressure.
     * Acts as a safety limit for the adaptive sizing.
     */
    private static int getMemorySafeBatchSize() {
        long heapUsed = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long heapMax = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        double memoryUsage = (double) heapUsed / heapMax;

        if (memoryUsage > 0.9) return 4;
        if (memoryUsage > 0.8) return 12;
        if (memoryUsage > 0.7) return 24;
        return 64; // No memory constraint
    }

    private static void updateMemoryPressure() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastMemoryCheck < MEMORY_CHECK_INTERVAL) {
            return;
        }

        lastMemoryCheck = currentTime;

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        double memoryUsage = (double) usedMemory / maxMemory;

        boolean previousPressure = highMemoryPressure;
        highMemoryPressure = memoryUsage > HIGH_MEMORY_THRESHOLD;

        if (highMemoryPressure && !previousPressure) {
            System.out.println("ChunkManager: High memory pressure, reducing GL batch sizes.");
        } else if (!highMemoryPressure && previousPressure) {
            System.out.println("ChunkManager: Memory pressure relieved, resuming normal batch sizes.");
        }
    }

    /**
     * Determines if distant chunk mesh generation should be skipped.
     * Uses CCO state management for accurate chunk state queries.
     */
    public static boolean shouldSkipDistantChunkMesh(Chunk chunk, int playerChunkX, int playerChunkZ) {
        if (!optimizationsEnabled) {
            return false;
        }

        updateMemoryPressure();

        if (highMemoryPressure) {
            // Check if chunk is already being unloaded using CCO state
            if (chunk.getCcoStateManager().hasState(CcoChunkState.UNLOADING)) {
                return true; // Don't generate mesh for unloading chunks
            }

            int distance = Math.max(Math.abs(chunk.getChunkX() - playerChunkX), Math.abs(chunk.getChunkZ() - playerChunkZ));
            return distance > 6;
        }

        return false;
    }

    public static boolean isHighMemoryPressure() {
        updateMemoryPressure();
        return highMemoryPressure;
    }

    public static void setOptimizationsEnabled(boolean enabled) {
        optimizationsEnabled = enabled;
        System.out.println("ChunkManager Optimizations: " + (enabled ? "Enabled" : "Disabled"));
    }

    public static boolean areOptimizationsEnabled() {
        return optimizationsEnabled;
    }

    public void cleanup() {
        activeChunkPositions.clear();
        shutdown();
    }

    public void shutdown() {
        System.out.println("Shutting down chunk executor...");
        chunkExecutor.shutdown();
        try {
            if (!chunkExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("Chunk executor did not terminate in 5 seconds. Forcing shutdown...");
                chunkExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            System.err.println("Error while shutting down chunk executor: " + e.getMessage());
            chunkExecutor.shutdownNow();
        }
        System.out.println("Chunk executor shutdown complete.");
    }

}
