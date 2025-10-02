package com.stonebreak.world.chunk;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;
import com.stonebreak.world.chunk.api.commonChunkOperations.state.CcoAtomicStateManager;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Collections;
import java.util.HashSet;
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
    private final Set<World.ChunkPosition> activeChunkPositions = Collections.synchronizedSet(new HashSet<>());
    private float updateTimer = 0.0f;

    private final ExecutorService chunkExecutor = Executors.newFixedThreadPool(2);
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

        Set<World.ChunkPosition> requiredChunks = calculateRequiredChunks(playerChunkX, playerChunkZ);
        unloadExitedChunks(requiredChunks);
        loadEnteredChunks(requiredChunks);
        ensureVisibleChunksAreReady(playerChunkX, playerChunkZ);
    }

    private Set<World.ChunkPosition> calculateRequiredChunks(int playerChunkX, int playerChunkZ) {
        Set<World.ChunkPosition> required = new HashSet<>();
        int loadDistance = renderDistance + 1;
        for (int x = playerChunkX - loadDistance; x <= playerChunkX + loadDistance; x++) {
            for (int z = playerChunkZ - loadDistance; z <= playerChunkZ + loadDistance; z++) {
                required.add(world.getCachedChunkPosition(x, z));
            }
        }
        return required;
    }

    private void unloadExitedChunks(Set<World.ChunkPosition> requiredChunks) {
        Set<World.ChunkPosition> chunksToUnload = new HashSet<>(activeChunkPositions);
        chunksToUnload.removeAll(requiredChunks);

        if (!chunksToUnload.isEmpty()) {
            int totalCandidates = chunksToUnload.size();
            int protectedCount = 0;
            int unloadedCount = 0;

            System.out.println("Checking " + totalCandidates + " chunks for unloading...");

            for (World.ChunkPosition pos : chunksToUnload) {
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

                // Chunk is clean or doesn't exist, safe to unload normally
                // Remove from active chunks BEFORE attempting unload to prevent double-unload
                activeChunkPositions.remove(pos);

                // Submit unload operation with error handling
                chunkExecutor.submit(() -> {
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
                });
                unloadedCount++;
            }

            if (protectedCount > 0) {
                System.out.println("Saved and unloaded " + protectedCount + " dirty chunks with player edits. Unloaded " + unloadedCount + " clean chunks.");
                System.out.println("Total dirty chunks in world: " + world.getDirtyChunkCount() + ", Total loaded chunks: " + world.getLoadedChunkCount());
            } else if (unloadedCount > 0) {
                System.out.println("Unloaded " + unloadedCount + " chunks.");
            }
        }
    }

    /**
     * Saves a dirty chunk synchronously, then unloads it.
     * Uses CCO state management to ensure proper lifecycle transitions.
     * This ensures player edits are preserved while allowing normal chunk lifecycle.
     */
    private void saveAndUnloadDirtyChunk(World.ChunkPosition pos, Chunk chunk) {
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
                    System.out.println("[CCO-SAVE-UNLOAD] Successfully saved and unloaded dirty chunk (" + pos.getX() + ", " + pos.getZ() + ")");
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

    private void loadEnteredChunks(Set<World.ChunkPosition> requiredChunks) {
        Set<World.ChunkPosition> chunksToLoad = new HashSet<>(requiredChunks);
        chunksToLoad.removeAll(activeChunkPositions);

        if (!chunksToLoad.isEmpty()) {
            for (World.ChunkPosition pos : chunksToLoad) {
                if (activeChunkPositions.add(pos)) {
                    chunkExecutor.submit(() -> {
                        try {
                            long startTime = System.currentTimeMillis();
                            Chunk chunk = world.getChunkAt(pos.getX(), pos.getZ());
                            long endTime = System.currentTimeMillis();

                            // Log slow chunk loading
                            if (endTime - startTime > 500) { // More than 500ms
                                System.err.println("SLOW CHUNK LOAD: Chunk (" + pos.getX() + ", " + pos.getZ() + ") took " + (endTime - startTime) + "ms");
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

    public static int getOptimizedGLBatchSize() {
        if (!optimizationsEnabled) {
            return 32;
        }

        updateMemoryPressure();

        float deltaTime = Game.getDeltaTime();
        int baseBatchSize = getExistingBatchSize();

        if (!highMemoryPressure && deltaTime < 0.016f) {
            return Math.min(64, baseBatchSize * 2);
        }

        if (deltaTime > 0.025f) {
            return Math.max(2, baseBatchSize / 2);
        }

        return baseBatchSize;
    }

    private static int getExistingBatchSize() {
        long heapUsed = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long heapMax = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        double memoryUsage = (double) heapUsed / heapMax;

        if (memoryUsage > 0.9) return 4;
        if (memoryUsage > 0.8) return 8;
        if (memoryUsage > 0.7) return 16;
        return 32;
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
