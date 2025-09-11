package com.stonebreak.world.chunk;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.stonebreak.player.Player;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

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
            System.out.println("Unloading " + chunksToUnload.size() + " chunks.");
            for (World.ChunkPosition pos : chunksToUnload) {
                activeChunkPositions.remove(pos);
                chunkExecutor.submit(() -> world.unloadChunk(pos.getX(), pos.getZ()));
            }
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
                            world.getChunkAt(pos.getX(), pos.getZ());
                            long endTime = System.currentTimeMillis();
                            
                            // Log slow chunk loading
                            if (endTime - startTime > 500) { // More than 500ms
                                System.err.println("SLOW CHUNK LOAD: Chunk (" + pos.getX() + ", " + pos.getZ() + ") took " + (endTime - startTime) + "ms");
                                System.err.println("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB used");
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
    
    public static boolean shouldSkipDistantChunkMesh(Chunk chunk, int playerChunkX, int playerChunkZ) {
        if (!optimizationsEnabled) {
            return false;
        }
        
        updateMemoryPressure();
        
        if (highMemoryPressure) {
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