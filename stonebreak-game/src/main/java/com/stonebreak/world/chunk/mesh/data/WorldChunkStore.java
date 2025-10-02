package com.stonebreak.world.chunk.mesh.data;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.util.MemoryProfiler;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.mesh.builder.ChunkMeshBuildingPipeline;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.SaveService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages chunk storage and lifecycle following SOLID principles.
 *
 * Single Responsibility: Stores chunks and coordinates their lifecycle
 * Open/Closed: Extensible through listeners
 * Liskov Substitution: N/A (no inheritance)
 * Interface Segregation: Focused public API
 * Dependency Inversion: Depends on abstractions (interfaces/injected dependencies)
 */
public class WorldChunkStore {
    // Dependencies (injected, following DI principle)
    private final TerrainGenerationSystem terrainSystem;
    private final WorldConfiguration config;
    private final ChunkMeshBuildingPipeline meshPipeline;

    // Core storage
    private final Map<World.ChunkPosition, Chunk> chunks;
    private final ChunkPositionCache positionCache;

    // Lifecycle listeners (optional, following Open/Closed)
    private Consumer<Chunk> chunkLoadListener;
    private Consumer<Chunk> chunkUnloadListener;

    public WorldChunkStore(TerrainGenerationSystem terrainSystem,
                          WorldConfiguration config,
                          ChunkMeshBuildingPipeline meshPipeline) {
        this.terrainSystem = terrainSystem;
        this.config = config;
        this.meshPipeline = meshPipeline;
        this.chunks = new ConcurrentHashMap<>();
        this.positionCache = new ChunkPositionCache();
    }

    // ========== Lifecycle Listeners ==========

    public void setChunkListeners(Consumer<Chunk> loadListener, Consumer<Chunk> unloadListener) {
        this.chunkLoadListener = loadListener;
        this.chunkUnloadListener = unloadListener;
    }

    // ========== Chunk Access ==========

    public Chunk getChunk(int x, int z) {
        World.ChunkPosition position = positionCache.get(x, z);
        return chunks.get(position);
    }

    public Chunk getOrCreateChunk(int x, int z) {
        World.ChunkPosition position = positionCache.get(x, z);
        Chunk chunk = chunks.get(position);

        if (chunk == null) {
            chunk = loadOrGenerateChunk(x, z, position);
        }

        return chunk;
    }

    public boolean hasChunk(int x, int z) {
        return chunks.containsKey(positionCache.get(x, z));
    }

    public void ensureChunkExists(int x, int z) {
        getOrCreateChunk(x, z);
    }

    public World.ChunkPosition getCachedChunkPosition(int x, int z) {
        return positionCache.get(x, z);
    }

    // ========== Chunk Collections ==========

    public Set<World.ChunkPosition> getAllChunkPositions() {
        return new HashSet<>(chunks.keySet());
    }

    public Collection<Chunk> getAllChunks() {
        return new ArrayList<>(chunks.values());
    }

    public List<Chunk> getDirtyChunks() {
        List<Chunk> dirtyChunks = new ArrayList<>();
        for (Chunk chunk : chunks.values()) {
            if (chunk != null && chunk.isDirty()) {
                dirtyChunks.add(chunk);
            }
        }
        return dirtyChunks;
    }

    public int getLoadedChunkCount() {
        return chunks.size();
    }

    // ========== Render Distance Management ==========

    public Map<World.ChunkPosition, Chunk> getChunksInRenderDistance(int playerChunkX, int playerChunkZ) {
        Map<World.ChunkPosition, Chunk> visibleChunks = new HashMap<>();
        int renderDistance = config.getRenderDistance();

        for (int x = playerChunkX - renderDistance; x <= playerChunkX + renderDistance; x++) {
            for (int z = playerChunkZ - renderDistance; z <= playerChunkZ + renderDistance; z++) {
                Chunk chunk = getOrCreateChunk(x, z);
                if (chunk != null) {
                    World.ChunkPosition position = positionCache.get(x, z);
                    visibleChunks.put(position, chunk);
                }
            }
        }

        return visibleChunks;
    }

    // ========== Chunk Unloading ==========

    public void unloadChunk(int chunkX, int chunkZ) {
        World.ChunkPosition pos = positionCache.get(chunkX, chunkZ);
        Chunk chunk = chunks.remove(pos);

        if (chunk == null) return;

        // Notify listener
        notifyUnload(chunk);

        // Save if dirty
        saveIfDirty(chunk);

        // Cleanup resources
        cleanupChunkResources(chunk, chunkX, chunkZ);

        // Remove from cache
        positionCache.remove(chunkX, chunkZ);

        // Optional memory logging
        logMemoryIfNeeded(chunkX, chunkZ);
    }

    // ========== Direct Chunk Setting (for loading) ==========

    public void setChunk(int x, int z, Chunk chunk) {
        World.ChunkPosition position = positionCache.get(x, z);
        chunks.put(position, chunk);
    }

    // ========== Cleanup ==========

    public void cleanup() {
        for (Chunk chunk : chunks.values()) {
            if (chunk != null) {
                chunk.cleanupGpuResources();
            }
        }
        chunks.clear();
        positionCache.clear();
    }

    // ========== Position Cache Management ==========

    public void cleanupPositionCacheIfNeeded(int loadedChunks) {
        if (positionCache.size() > loadedChunks * 2) {
            positionCache.cleanupUnusedPositions(chunks.keySet());
            System.out.println("WARNING: " + loadedChunks + " chunks loaded, cleaned position cache");
        }
    }

    public void clearPositionCacheIfLarge(int threshold) {
        if (positionCache.size() > threshold) {
            positionCache.clear();
        }
    }

    // ========== Private Helper Methods (KISS - each does one thing) ==========

    /**
     * Load from save if available, otherwise generate new chunk.
     */
    private Chunk loadOrGenerateChunk(int x, int z, World.ChunkPosition position) {
        SaveService saveService = getSaveSystem();

        if (saveService != null) {
            Chunk savedChunk = tryLoadFromSave(x, z, saveService);
            if (savedChunk != null) {
                registerLoadedChunk(position, savedChunk);
                return savedChunk;
            }
        }

        return generateNewChunk(x, z);
    }

    private Chunk tryLoadFromSave(int x, int z, SaveService saveService) {
        try {
            Chunk chunk = saveService.loadChunk(x, z).get();
            if (chunk != null) {
                prepareLoadedChunk(chunk);
                return chunk;
            }
        } catch (Exception e) {
            System.err.println("[LOAD] Failed to load chunk (" + x + ", " + z + "): " + e.getMessage());
        }
        return null;
    }

    private void prepareLoadedChunk(Chunk chunk) {
        // Ensure features flag is set to prevent regeneration
        if (!chunk.areFeaturesPopulated()) {
            chunk.setFeaturesPopulated(true);
        }

        // Force mesh rebuild
        chunk.cleanupCpuResources();
        meshPipeline.scheduleConditionalMeshBuild(chunk);
    }

    private void registerLoadedChunk(World.ChunkPosition position, Chunk chunk) {
        chunks.put(position, chunk);
        notifyLoad(chunk);
    }

    private Chunk generateNewChunk(int x, int z) {
        World.ChunkPosition position = positionCache.get(x, z);

        // Double-check to prevent race condition
        if (chunks.containsKey(position)) {
            return chunks.get(position);
        }

        try {
            MemoryProfiler.getInstance().incrementAllocation("Chunk");

            Chunk chunk = terrainSystem.generateBareChunk(x, z);
            if (chunk == null) {
                System.err.println("CRITICAL: Failed to generate chunk at (" + x + ", " + z + ")");
                return null;
            }

            chunks.put(position, chunk);
            notifyLoad(chunk);

            if (shouldQueueForMesh(x, z)) {
                meshPipeline.scheduleConditionalMeshBuild(chunk);
            }

            return chunk;
        } catch (Exception e) {
            System.err.println("Exception generating chunk (" + x + ", " + z + "): " + e.getMessage());
            chunks.remove(position);
            return null;
        }
    }

    private boolean shouldQueueForMesh(int chunkX, int chunkZ) {
        Player player = Game.getPlayer();
        if (player == null) return true;

        int playerChunkX = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        int distance = Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ));

        return distance <= config.getBorderChunkDistance();
    }

    private void saveIfDirty(Chunk chunk) {
        if (!chunk.isDirty()) return;

        SaveService saveService = getSaveSystem();
        if (saveService == null) {
            System.err.println("CRITICAL: Cannot save dirty chunk (" + chunk.getX() + ", " + chunk.getZ() + ") - save system unavailable");
            throw new RuntimeException("Cannot safely unload chunk with unsaved edits - save system is null");
        }

        try {
            saveService.saveChunk(chunk).get(); // Synchronous save
            System.out.println("[SAVE] Saved dirty chunk (" + chunk.getX() + ", " + chunk.getZ() + ")");
        } catch (Exception e) {
            String error = extractErrorMessage(e);
            System.err.println("CRITICAL: Failed to save dirty chunk (" + chunk.getX() + ", " + chunk.getZ() + "): " + error);
            System.err.println("Full exception details:");
            e.printStackTrace();
            throw new RuntimeException("Cannot safely unload chunk with unsaved edits: " + error, e);
        }
    }

    private void cleanupChunkResources(Chunk chunk, int chunkX, int chunkZ) {
        meshPipeline.removeChunkFromQueues(chunk);
        chunk.cleanupCpuResources();
        meshPipeline.addChunkForGpuCleanup(chunk);
        Game.getEntityManager().removeEntitiesInChunk(chunkX, chunkZ);
    }

    private void notifyLoad(Chunk chunk) {
        if (chunkLoadListener != null) {
            chunkLoadListener.accept(chunk);
        }
    }

    private void notifyUnload(Chunk chunk) {
        if (chunkUnloadListener != null) {
            chunkUnloadListener.accept(chunk);
        }
    }

    private void logMemoryIfNeeded(int chunkX, int chunkZ) {
        if ((chunkX + chunkZ) % WorldConfiguration.MEMORY_LOG_INTERVAL == 0) {
            Game.logDetailedMemoryInfo("After unloading chunk (" + chunkX + ", " + chunkZ + ")");
        }
    }

    private SaveService getSaveSystem() {
        Game game = Game.getInstance();
        return (game != null) ? game.getSaveService() : null;
    }

    private String extractErrorMessage(Exception e) {
        // Try to get the most descriptive error message available
        String msg = e.getMessage();

        if (msg == null || msg.isEmpty()) {
            if (e.getCause() != null) {
                msg = e.getCause().getMessage();
            }
        }

        if (msg == null || msg.isEmpty()) {
            // Provide a descriptive fallback
            msg = e.getClass().getSimpleName();
            if (e.getCause() != null) {
                msg += " (caused by " + e.getCause().getClass().getSimpleName() + ")";
            }
        }

        return msg;
    }

    // ========== Inner Class: Position Cache (SRP) ==========

    private static class ChunkPositionCache {
        private static final int MAX_SIZE = WorldConfiguration.MAX_CHUNK_POSITION_CACHE_SIZE;
        private final Map<Long, World.ChunkPosition> cache = new ConcurrentHashMap<>();

        World.ChunkPosition get(int x, int z) {
            long key = makeKey(x, z);

            if (cache.size() > MAX_SIZE) {
                System.out.println("WARNING: Position cache exceeded max size, clearing");
                cache.clear();
            }

            return cache.computeIfAbsent(key, k -> new World.ChunkPosition(x, z));
        }

        void remove(int x, int z) {
            cache.remove(makeKey(x, z));
        }

        void clear() {
            cache.clear();
        }

        int size() {
            return cache.size();
        }

        void cleanupUnusedPositions(Set<World.ChunkPosition> loadedPositions) {
            System.out.println("Cleaning chunk position cache (size: " + cache.size() + ")");

            Set<Long> loadedKeys = new HashSet<>();
            for (World.ChunkPosition pos : loadedPositions) {
                loadedKeys.add(makeKey(pos.getX(), pos.getZ()));
            }

            cache.entrySet().removeIf(entry -> !loadedKeys.contains(entry.getKey()));

            System.out.println("Chunk position cache cleaned (new size: " + cache.size() + ")");
            MemoryProfiler.getInstance().takeSnapshot("after_chunk_position_cache_cleanup");
        }

        private long makeKey(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
    }
}
