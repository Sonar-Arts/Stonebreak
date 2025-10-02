package com.stonebreak.world.chunk.utils;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.util.MemoryProfiler;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshPipeline;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.SaveService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages chunk storage and lifecycle with CCO-integrated serialization.
 * Follows SOLID, KISS, YAGNI, and DRY principles.
 */
public class WorldChunkStore {
    private final TerrainGenerationSystem terrainSystem;
    private final WorldConfiguration config;
    private final MmsMeshPipeline meshPipeline;
    private final Map<World.ChunkPosition, Chunk> chunks;
    private final PositionCache positionCache;

    private Consumer<Chunk> loadListener;
    private Consumer<Chunk> unloadListener;

    public WorldChunkStore(TerrainGenerationSystem terrainSystem,
                          WorldConfiguration config,
                          MmsMeshPipeline meshPipeline) {
        this.terrainSystem = terrainSystem;
        this.config = config;
        this.meshPipeline = meshPipeline;
        this.chunks = new ConcurrentHashMap<>();
        this.positionCache = new PositionCache();
    }

    // ========== Public API ==========

    public void setChunkListeners(Consumer<Chunk> loadListener, Consumer<Chunk> unloadListener) {
        this.loadListener = loadListener;
        this.unloadListener = unloadListener;
    }

    public Chunk getChunk(int x, int z) {
        return chunks.get(positionCache.get(x, z));
    }

    public Chunk getOrCreateChunk(int x, int z) {
        World.ChunkPosition pos = positionCache.get(x, z);
        return chunks.computeIfAbsent(pos, p -> loadOrGenerate(x, z));
    }

    public boolean hasChunk(int x, int z) {
        return chunks.containsKey(positionCache.get(x, z));
    }

    public void ensureChunkExists(int x, int z) {
        getOrCreateChunk(x, z);
    }

    public Collection<Chunk> getAllChunks() {
        return new ArrayList<>(chunks.values());
    }

    public Set<World.ChunkPosition> getAllChunkPositions() {
        return new HashSet<>(chunks.keySet());
    }

    public List<Chunk> getDirtyChunks() {
        List<Chunk> dirty = new ArrayList<>();
        for (Chunk chunk : chunks.values()) {
            if (chunk != null && chunk.isDirty()) {
                dirty.add(chunk);
            }
        }
        return dirty;
    }

    public int getLoadedChunkCount() {
        return chunks.size();
    }

    public Map<World.ChunkPosition, Chunk> getChunksInRenderDistance(int playerChunkX, int playerChunkZ) {
        Map<World.ChunkPosition, Chunk> visible = new HashMap<>();
        int renderDist = config.getRenderDistance();

        for (int x = playerChunkX - renderDist; x <= playerChunkX + renderDist; x++) {
            for (int z = playerChunkZ - renderDist; z <= playerChunkZ + renderDist; z++) {
                Chunk chunk = getOrCreateChunk(x, z);
                if (chunk != null) {
                    visible.put(positionCache.get(x, z), chunk);
                }
            }
        }
        return visible;
    }

    public void unloadChunk(int chunkX, int chunkZ) {
        World.ChunkPosition pos = positionCache.get(chunkX, chunkZ);
        Chunk chunk = chunks.remove(pos);
        if (chunk == null) return;

        notify(unloadListener, chunk);
        saveIfDirty(chunk);
        cleanup(chunk, chunkX, chunkZ);
        positionCache.remove(chunkX, chunkZ);

        if ((chunkX + chunkZ) % WorldConfiguration.MEMORY_LOG_INTERVAL == 0) {
            Game.logDetailedMemoryInfo("After unloading chunk (" + chunkX + ", " + chunkZ + ")");
        }
    }

    public void setChunk(int x, int z, Chunk chunk) {
        chunks.put(positionCache.get(x, z), chunk);
    }

    public void cleanup() {
        chunks.values().forEach(chunk -> {
            if (chunk != null) chunk.cleanupGpuResources();
        });
        chunks.clear();
        positionCache.clear();
    }

    public World.ChunkPosition getCachedChunkPosition(int x, int z) {
        return positionCache.get(x, z);
    }

    public void cleanupPositionCacheIfNeeded(int loadedChunks) {
        if (positionCache.size() > loadedChunks * 2) {
            positionCache.cleanup(chunks.keySet());
            System.out.println("Position cache cleaned: " + loadedChunks + " chunks loaded");
        }
    }

    public void clearPositionCacheIfLarge(int threshold) {
        if (positionCache.size() > threshold) {
            positionCache.clear();
        }
    }

    // ========== Private Implementation ==========

    /**
     * Loads chunk from save or generates new one. Uses CCO snapshots via SaveService.
     */
    private Chunk loadOrGenerate(int x, int z) {
        SaveService saveService = getSaveService();

        // Try loading from disk first
        if (saveService != null) {
            try {
                Chunk loaded = saveService.loadChunk(x, z).get();
                if (loaded != null) {
                    prepareLoadedChunk(loaded);
                    notify(loadListener, loaded);
                    return loaded;
                }
            } catch (Exception e) {
                System.err.println("[LOAD] Failed to load chunk (" + x + ", " + z + "): " + e.getMessage());
            }
        }

        // Generate new chunk
        return generate(x, z);
    }

    private Chunk generate(int x, int z) {
        try {
            MemoryProfiler.getInstance().incrementAllocation("Chunk");

            Chunk chunk = terrainSystem.generateBareChunk(x, z);
            if (chunk == null) {
                System.err.println("CRITICAL: Failed to generate chunk at (" + x + ", " + z + ")");
                return null;
            }

            notify(loadListener, chunk);

            if (shouldMesh(x, z)) {
                meshPipeline.scheduleConditionalMeshBuild(chunk);
            }

            return chunk;
        } catch (Exception e) {
            System.err.println("Exception generating chunk (" + x + ", " + z + "): " + e.getMessage());
            return null;
        }
    }

    private void prepareLoadedChunk(Chunk chunk) {
        if (!chunk.areFeaturesPopulated()) {
            chunk.setFeaturesPopulated(true);
        }
        chunk.cleanupCpuResources();
        meshPipeline.scheduleConditionalMeshBuild(chunk);
    }

    private boolean shouldMesh(int chunkX, int chunkZ) {
        Player player = Game.getPlayer();
        if (player == null) return true;

        int playerChunkX = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        int distance = Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ));

        return distance <= config.getBorderChunkDistance();
    }

    /**
     * Saves dirty chunk using CCO integration.
     * SaveService internally uses chunk.createSnapshot() via StateConverter.
     */
    private void saveIfDirty(Chunk chunk) {
        if (!chunk.isDirty()) return;

        SaveService saveService = getSaveService();
        if (saveService == null) {
            throw new RuntimeException("Cannot save dirty chunk (" + chunk.getX() + ", " + chunk.getZ() + ") - save system unavailable");
        }

        try {
            saveService.saveChunk(chunk).get();
            System.out.println("[SAVE] Saved chunk (" + chunk.getX() + ", " + chunk.getZ() + ")");
        } catch (Exception e) {
            System.err.println("CRITICAL: Failed to save chunk (" + chunk.getX() + ", " + chunk.getZ() + "): " + getErrorMessage(e));
            e.printStackTrace();
            throw new RuntimeException("Cannot unload chunk with unsaved edits", e);
        }
    }

    private void cleanup(Chunk chunk, int chunkX, int chunkZ) {
        meshPipeline.removeChunkFromQueues(chunk);
        chunk.cleanupCpuResources();
        meshPipeline.addChunkForGpuCleanup(chunk);
        Game.getEntityManager().removeEntitiesInChunk(chunkX, chunkZ);
    }

    private void notify(Consumer<Chunk> listener, Chunk chunk) {
        if (listener != null) listener.accept(chunk);
    }

    private SaveService getSaveService() {
        Game game = Game.getInstance();
        return (game != null) ? game.getSaveService() : null;
    }

    private String getErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName();
        }
        return msg != null ? msg : "Unknown error";
    }

    // ========== Position Cache ==========

    private static class PositionCache {
        private static final int MAX_SIZE = WorldConfiguration.MAX_CHUNK_POSITION_CACHE_SIZE;
        private final Map<Long, World.ChunkPosition> cache = new ConcurrentHashMap<>();

        World.ChunkPosition get(int x, int z) {
            if (cache.size() > MAX_SIZE) {
                System.out.println("WARNING: Position cache exceeded max size, clearing");
                cache.clear();
            }
            return cache.computeIfAbsent(key(x, z), k -> new World.ChunkPosition(x, z));
        }

        void remove(int x, int z) {
            cache.remove(key(x, z));
        }

        void clear() {
            cache.clear();
        }

        int size() {
            return cache.size();
        }

        void cleanup(Set<World.ChunkPosition> loadedPositions) {
            Set<Long> loadedKeys = new HashSet<>();
            loadedPositions.forEach(pos -> loadedKeys.add(key(pos.getX(), pos.getZ())));
            cache.keySet().removeIf(key -> !loadedKeys.contains(key));
            MemoryProfiler.getInstance().takeSnapshot("chunk_position_cache_cleanup");
        }

        private long key(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
    }
}
