package com.stonebreak.world.chunk.utils;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.util.MemoryProfiler;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.ChunkStatus;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshPipeline;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.generation.features.FeatureQueue;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.SaveService;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Manages chunk storage and lifecycle with CCO-integrated serialization.
 * Implements Minecraft-style multi-stage chunk generation to prevent feature "pop-in".
 * Follows SOLID, KISS, YAGNI, and DRY principles.
 */
public class WorldChunkStore {
    private final TerrainGenerationSystem terrainSystem;
    private final WorldConfiguration config;
    private final MmsMeshPipeline meshPipeline;
    private final Map<ChunkPosition, Chunk> chunks;
    private final PositionCache positionCache;
    private final com.stonebreak.world.World world;
    private final FeatureQueue featureQueue;

    // Deferred feature population queue to break recursive generation cycles
    private final Queue<ChunkPosition> pendingFeaturePopulation = new ConcurrentLinkedQueue<>();

    private Consumer<Chunk> loadListener;
    private Consumer<Chunk> unloadListener;

    public WorldChunkStore(TerrainGenerationSystem terrainSystem,
                          WorldConfiguration config,
                          MmsMeshPipeline meshPipeline,
                          com.stonebreak.world.World world,
                          FeatureQueue featureQueue) {
        this.terrainSystem = terrainSystem;
        this.config = config;
        this.meshPipeline = meshPipeline;
        this.world = world;
        this.featureQueue = featureQueue;

        // OPTIMIZATION: Pre-size ConcurrentHashMap to avoid resizing overhead
        // Calculate expected capacity: (renderDistance * 2 + 3)² chunks
        // +3 accounts for border chunks (renderDistance + 1 on each side + center)
        // Multiply by 2 for HashMap load factor (default 0.75, use 0.5 for safety)
        int renderDist = config.getRenderDistance();
        int expectedChunks = (renderDist * 2 + 3) * (renderDist * 2 + 3);
        int initialCapacity = expectedChunks * 2; // Account for load factor
        this.chunks = new ConcurrentHashMap<>(initialCapacity);

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
        ChunkPosition pos = positionCache.get(x, z);
        Chunk chunk = chunks.get(pos);

        if (chunk == null) {
            // Generate/load chunk (features deferred to prevent recursion)
            chunk = chunks.computeIfAbsent(pos, p -> loadOrGenerate(x, z));
            if (chunk != null) {
                // Queue for deferred feature population if not already populated
                if (!chunk.areFeaturesPopulated()) {
                    pendingFeaturePopulation.offer(pos);
                }

                // Process queued features that were waiting for this chunk
                featureQueue.processChunk(world, pos);

                if (loadListener != null) {
                    notify(loadListener, chunk);
                }
            }
        }

        return chunk;
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

    public Set<ChunkPosition> getAllChunkPositions() {
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

    public Map<ChunkPosition, Chunk> getChunksInRenderDistance(int playerChunkX, int playerChunkZ) {
        Map<ChunkPosition, Chunk> visible = new HashMap<>();
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
        ChunkPosition pos = positionCache.get(chunkX, chunkZ);
        Chunk chunk = chunks.remove(pos);
        if (chunk == null) {
            // Chunk doesn't exist, but still clean up position cache entry
            positionCache.remove(chunkX, chunkZ);
            return;
        }

        // CRITICAL FIX: Save chunk BEFORE notifying unload listener!
        // The unload listener (waterSystem.onChunkUnloaded) deletes water cells from WaterSystem.
        // We must save the chunk first so extractWaterMetadata() can access those cells.

        // OPTIMIZATION: Async save - don't block main thread
        // Save dirty chunks asynchronously, cleanup after save completes
        if (chunk.isDirty()) {
            saveIfDirtyAsync(chunk).thenRun(() -> {
                // AFTER save completes, notify unload listener to clean up water cells
                notify(unloadListener, chunk);
                cleanup(chunk, chunkX, chunkZ);
                positionCache.remove(chunkX, chunkZ);
            }).exceptionally(ex -> {
                System.err.println("CRITICAL: Async save failed for chunk (" + chunkX + ", " + chunkZ + "): " + ex.getMessage());
                // Still cleanup to prevent memory leak
                notify(unloadListener, chunk);
                cleanup(chunk, chunkX, chunkZ);
                positionCache.remove(chunkX, chunkZ);
                return null;
            });
        } else {
            // Clean chunks can be unloaded immediately
            notify(unloadListener, chunk);
            cleanup(chunk, chunkX, chunkZ);
            positionCache.remove(chunkX, chunkZ);
        }

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
        featureQueue.clear();
    }

    public ChunkPosition getCachedChunkPosition(int x, int z) {
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

    /**
     * Processes chunks waiting for feature population. Called each frame from World.update().
     * Only populates features for chunks whose neighbors exist to prevent recursion.
     * Limits processing to prevent lag spikes.
     */
    public void processPendingFeaturePopulation() {
        ChunkPosition pos;
        int processed = 0;
        final int MAX_PER_FRAME = 10; // Limit to prevent lag spikes

        while (processed < MAX_PER_FRAME && (pos = pendingFeaturePopulation.poll()) != null) {
            Chunk chunk = chunks.get(pos);

            // Skip if chunk was unloaded or already has features
            if (chunk == null || chunk.areFeaturesPopulated()) {
                continue;
            }

            // Check if all required neighbors exist (east, south, southeast)
            int x = pos.getX();
            int z = pos.getZ();
            boolean neighborsReady = hasChunk(x + 1, z) &&
                                   hasChunk(x, z + 1) &&
                                   hasChunk(x + 1, z + 1);

            if (neighborsReady) {
                // Neighbors exist - safe to populate features
                try {
                    terrainSystem.populateChunkWithFeatures(world, chunk, world.getSnowLayerManager());
                    chunk.setFeaturesPopulated(true);
                    processed++;
                } catch (Exception e) {
                    System.err.println("Exception populating features for chunk (" + x + ", " + z + "): " + e.getMessage());
                    // Mark as populated anyway to prevent infinite retry
                    chunk.setFeaturesPopulated(true);
                }
            } else {
                // Re-queue if neighbors not ready yet
                pendingFeaturePopulation.offer(pos);
                break; // Stop processing to prevent spinning on same chunk
            }
        }
    }

    // ========== Private Implementation ==========

    /**
     * Loads chunk from save or generates new one. Uses CCO snapshots via SaveService.
     * Features are deferred via queue to prevent recursive chunk generation.
     */
    private Chunk loadOrGenerate(int x, int z) {
        SaveService saveService = getSaveService();

        // Try loading from disk first
        if (saveService != null) {
            try {
                Chunk loaded = saveService.loadChunk(x, z).get();
                if (loaded != null) {
                    prepareLoadedChunk(loaded);
                    return loaded;
                }
            } catch (Exception e) {
                System.err.println("[LOAD] Failed to load chunk (" + x + ", " + z + "): " + e.getMessage());
            }
        }

        // Generate new chunk (terrain only, features deferred)
        return generate(x, z);
    }

    private Chunk generate(int x, int z) {
        try {
            MemoryProfiler.getInstance().incrementAllocation("Chunk");

            // Generate terrain ONLY - features will be populated later via queue
            // This prevents recursive chunk generation during mesh building
            Chunk chunk = terrainSystem.generateTerrainOnly(x, z);
            if (chunk == null) {
                System.err.println("CRITICAL: Failed to generate chunk at (" + x + ", " + z + ")");
                return null;
            }

            // DO NOT populate features here - they will be populated by processPendingFeaturePopulation()
            // when neighbors exist and it's safe to do so without triggering recursion

            // CRITICAL FIX: Mark newly generated chunks as clean UNLESS they contain flowing water
            // setBlock() calls during generation marked them dirty, but they don't
            // need saving until the PLAYER modifies them. This prevents all 3000+
            // generated chunks from staying dirty forever and never being unloaded.
            // EXCEPTION: Chunks with flowing water MUST be saved to persist water metadata.
            if (!chunkHasFlowingWater(chunk)) {
                chunk.markClean();
            } else {
                System.out.println("[WATER-GEN] Chunk (" + x + ", " + z + ") has flowing water - keeping dirty for save");
            }

            return chunk;
        } catch (Exception e) {
            System.err.println("Exception generating chunk (" + x + ", " + z + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * Checks if a chunk contains any flowing (non-source) water blocks.
     * Used to determine if a newly generated chunk needs to be saved to persist water metadata.
     */
    private boolean chunkHasFlowingWater(Chunk chunk) {
        if (world == null || world.getWaterSystem() == null) {
            return false;
        }

        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();

        // Scan chunk for water blocks and check their state in WaterSystem
        for (int localX = 0; localX < WorldConfiguration.CHUNK_SIZE; localX++) {
            for (int localZ = 0; localZ < WorldConfiguration.CHUNK_SIZE; localZ++) {
                for (int y = 0; y < WorldConfiguration.WORLD_HEIGHT; y++) {
                    if (chunk.getBlock(localX, y, localZ) == com.stonebreak.blocks.BlockType.WATER) {
                        int worldX = chunkX * WorldConfiguration.CHUNK_SIZE + localX;
                        int worldZ = chunkZ * WorldConfiguration.CHUNK_SIZE + localZ;

                        var waterBlock = world.getWaterSystem().getWaterBlock(worldX, y, worldZ);
                        // If there's any non-source water, this chunk needs to be saved
                        if (waterBlock != null && !waterBlock.isSource()) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
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
     * Saves dirty chunk asynchronously using CCO integration.
     * SaveService internally uses chunk.createSnapshot() via StateConverter.
     * OPTIMIZATION: Returns CompletableFuture to avoid blocking main thread.
     */
    private CompletableFuture<Void> saveIfDirtyAsync(Chunk chunk) {
        if (!chunk.isDirty()) {
            return CompletableFuture.completedFuture(null);
        }

        SaveService saveService = getSaveService();
        if (saveService == null) {
            return CompletableFuture.failedFuture(
                new RuntimeException("Cannot save dirty chunk (" + chunk.getX() + ", " + chunk.getZ() + ") - save system unavailable")
            );
        }

        return saveService.saveChunk(chunk)
            .thenRun(() -> {
                // Logging removed for performance - chunk saves happen frequently
            })
            .exceptionally(ex -> {
                System.err.println("CRITICAL: Failed to save chunk (" + chunk.getX() + ", " + chunk.getZ() + "): " + getErrorMessage((Exception) ex));
                return null;
            });
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
        private final Map<Long, ChunkPosition> cache = new ConcurrentHashMap<>();

        ChunkPosition get(int x, int z) {
            if (cache.size() > MAX_SIZE) {
                System.out.println("WARNING: Position cache exceeded max size, clearing");
                cache.clear();
            }
            return cache.computeIfAbsent(key(x, z), k -> new ChunkPosition(x, z));
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

        void cleanup(Set<ChunkPosition> loadedPositions) {
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
