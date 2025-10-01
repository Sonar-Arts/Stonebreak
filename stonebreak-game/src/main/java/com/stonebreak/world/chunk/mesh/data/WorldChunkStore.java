package com.stonebreak.world.chunk.mesh.data;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.util.MemoryProfiler;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.mesh.builder.ChunkMeshBuildingPipeline;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class WorldChunkStore {
    private final Map<World.ChunkPosition, Chunk> chunks;
    private final Map<Long, World.ChunkPosition> chunkPositionCache;
    private final TerrainGenerationSystem terrainSystem;
    private final WorldConfiguration config;
    private final ChunkMeshBuildingPipeline meshPipeline;
    private Consumer<Chunk> chunkLoadListener;
    private Consumer<Chunk> chunkUnloadListener;

    public WorldChunkStore(TerrainGenerationSystem terrainSystem, WorldConfiguration config, ChunkMeshBuildingPipeline meshPipeline) {
        this.chunks = new ConcurrentHashMap<>();
        this.chunkPositionCache = new ConcurrentHashMap<>();
        this.terrainSystem = terrainSystem;
        this.config = config;
        this.meshPipeline = meshPipeline;
    }

    public void setChunkListeners(Consumer<Chunk> loadListener, Consumer<Chunk> unloadListener) {
        this.chunkLoadListener = loadListener;
        this.chunkUnloadListener = unloadListener;
    }

    public World.ChunkPosition getCachedChunkPosition(int x, int z) {
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        
        if (chunkPositionCache.size() > WorldConfiguration.MAX_CHUNK_POSITION_CACHE_SIZE) {
            cleanupChunkPositionCache();
        }
        
        return chunkPositionCache.computeIfAbsent(key, k -> new World.ChunkPosition(x, z));
    }
    
    public Chunk getChunk(int x, int z) {
        World.ChunkPosition position = getCachedChunkPosition(x, z);
        return chunks.get(position);
    }
    
    public Chunk getOrCreateChunk(int x, int z) {
        World.ChunkPosition position = getCachedChunkPosition(x, z);
        Chunk chunk = chunks.get(position);

        if (chunk == null) {
            // NEW SAVE-FIRST APPROACH: Check save data before generation
            chunk = loadChunkFromSaveOrGenerate(x, z, position);
        }

        return chunk;
    }

    /**
     * Implements save-first loading: load from save data if exists, otherwise generate and save.
     * This prevents world generation from overriding player modifications.
     */
    private Chunk loadChunkFromSaveOrGenerate(int x, int z, World.ChunkPosition position) {
        com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
        var saveSystem = (game != null) ? game.getWorldSaveSystem() : null;

        if (saveSystem != null && saveSystem.isInitialized()) {
            try {
                // Step 1: Check if saved chunk exists (synchronously)
                Boolean chunkExists = saveSystem.chunkExists(x, z).get();

                if (chunkExists != null && chunkExists) {
                    // Step 2: Load saved chunk (synchronously)
                    Chunk savedChunk = saveSystem.loadChunk(x, z).get();

                    if (savedChunk != null) {
                        // Step 3: Register saved chunk and return
                        chunks.put(position, savedChunk);

                        // Enhanced logging to track save state
                        String chunkState = savedChunk.isDirty() ? "DIRTY" : "CLEAN";
                        String featuresState = savedChunk.areFeaturesPopulated() ? "POPULATED" : "NOT_POPULATED";
                        System.out.println("[SAVE-FIRST] Loaded chunk (" + x + ", " + z + ") from save data - State: " + chunkState + ", Features: " + featuresState);

                        // Force mesh build for loaded chunk to ensure visibility
                        // Loaded chunks always need mesh rebuilds since they may have different states
                        savedChunk.cleanupCpuResources(); // Reset mesh state to force regeneration
                        if (shouldQueueForMesh(x, z)) {
                            meshPipeline.scheduleConditionalMeshBuild(savedChunk);
                        }

                        return savedChunk;
                    } else {
                        System.err.println("[SAVE-FIRST] WARNING: Chunk exists in storage but loaded as null (" + x + ", " + z + ")");
                    }
                }
            } catch (Exception e) {
                System.err.println("Error loading chunk (" + x + ", " + z + ") from save data: " + e.getMessage());
                System.out.println("Falling back to generation for chunk (" + x + ", " + z + ")");
            }
        }

        // Step 4: No save data exists or save system unavailable - generate new chunk
        Chunk generatedChunk = safelyGenerateAndRegisterChunk(x, z);

        // Step 5: Immediately save newly generated chunk to create baseline
        if (generatedChunk != null && saveSystem != null && saveSystem.isInitialized()) {
            try {
                String featuresState = generatedChunk.areFeaturesPopulated() ? "POPULATED" : "NOT_POPULATED";
                System.out.println("[SAVE-FIRST] Saving newly generated chunk (" + x + ", " + z + ") as baseline - Features: " + featuresState);

                saveSystem.saveChunk(generatedChunk)
                    .thenRun(() -> {
                        generatedChunk.markClean(); // Mark as clean since it's saved
                        System.out.println("[SAVE-FIRST] Successfully saved newly generated chunk (" + x + ", " + z + ") as baseline - chunk now CLEAN");
                    })
                    .exceptionally(ex -> {
                        System.err.println("CRITICAL: Failed to save newly generated chunk (" + x + ", " + z + "): " + ex.getMessage());
                        // Keep chunk dirty if save failed
                        return null;
                    });
            } catch (Exception e) {
                System.err.println("CRITICAL: Error saving newly generated chunk (" + x + ", " + z + "): " + e.getMessage());
            }
        } else {
            System.err.println("[SAVE-FIRST] WARNING: Cannot save newly generated chunk (" + x + ", " + z + ") - save system not available");
        }

        return generatedChunk;
    }

    public void ensureChunkExists(int x, int z) {
        getOrCreateChunk(x, z);
    }
    
    public boolean hasChunk(int x, int z) {
        return chunks.containsKey(getCachedChunkPosition(x, z));
    }
    
    public Set<World.ChunkPosition> getAllChunkPositions() {
        return new HashSet<>(chunks.keySet());
    }

    public Collection<Chunk> getAllChunks() {
        return new ArrayList<>(chunks.values());
    }

    public int getLoadedChunkCount() {
        return chunks.size();
    }
    
    /**
     * Sets a chunk at the given position (used for world loading)
     */
    public void setChunk(int x, int z, Chunk chunk) {
        World.ChunkPosition position = getCachedChunkPosition(x, z);
        chunks.put(position, chunk);
    }

    public void unloadChunk(int chunkX, int chunkZ) {
        World.ChunkPosition pos = getCachedChunkPosition(chunkX, chunkZ);
        Chunk chunk = chunks.remove(pos);

        if (chunk != null) {
            if (chunkUnloadListener != null) {
                chunkUnloadListener.accept(chunk);
            }
            // Save dirty chunks before unloading to preserve player edits
            if (chunk.isDirty()) {
                saveChunkOnUnload(chunk);
            }

            meshPipeline.removeChunkFromQueues(chunk);
            chunk.cleanupCpuResources();
            meshPipeline.addChunkForGpuCleanup(chunk);

            Game.getEntityManager().removeEntitiesInChunk(chunkX, chunkZ);

            long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            chunkPositionCache.remove(key);

            if ((chunkX + chunkZ) % WorldConfiguration.MEMORY_LOG_INTERVAL == 0) {
                Game.logDetailedMemoryInfo("After unloading chunk (" + chunkX + ", " + chunkZ + ")");
            }
        }
    }

    /**
     * Saves a dirty chunk synchronously when it's being unloaded.
     * This ensures player edits are preserved and save completes before unload.
     * CRITICAL: This must be synchronous to prevent race conditions.
     */
    private void saveChunkOnUnload(Chunk chunk) {
        var game = Game.getInstance();
        var saveSystem = (game != null) ? game.getWorldSaveSystem() : null;

        if (saveSystem != null && saveSystem.isInitialized()) {
            try {
                // Save the chunk SYNCHRONOUSLY to ensure completion before unload
                saveSystem.saveChunk(chunk).get(); // Wait for save completion
                chunk.markClean(); // Mark as clean only after successful save
                System.out.println("[SYNC-SAVE] Saved dirty chunk (" + chunk.getX() + ", " + chunk.getZ() + ") on unload - edits preserved");
            } catch (Exception ex) {
                System.err.println("CRITICAL: Failed to save dirty chunk (" + chunk.getX() + ", " + chunk.getZ() + ") on unload: " + ex.getMessage());
                // Don't mark as clean if save failed - keep chunk dirty
                throw new RuntimeException("Cannot safely unload chunk with unsaved edits at (" + chunk.getX() + ", " + chunk.getZ() + ")", ex);
            }
        } else {
            System.err.println("CRITICAL: Cannot save dirty chunk (" + chunk.getX() + ", " + chunk.getZ() + ") on unload - save system not available");
            throw new RuntimeException("Cannot safely unload chunk with unsaved edits - save system unavailable");
        }
    }

    public Map<World.ChunkPosition, Chunk> getChunksInRenderDistance(int playerChunkX, int playerChunkZ) {
        Map<World.ChunkPosition, Chunk> visibleChunks = new HashMap<>();
        int renderDistance = config.getRenderDistance();
        
        for (int x = playerChunkX - renderDistance; x <= playerChunkX + renderDistance; x++) {
            for (int z = playerChunkZ - renderDistance; z <= playerChunkZ + renderDistance; z++) {
                World.ChunkPosition position = getCachedChunkPosition(x, z);
                Chunk chunk = getOrCreateChunk(x, z);
                
                if (chunk != null) {
                    visibleChunks.put(position, chunk);
                }
            }
        }
        
        return visibleChunks;
    }
    
    public void cleanupPositionCacheIfNeeded(int loadedChunks) {
        if (chunkPositionCache.size() > loadedChunks * 2) {
            cleanupChunkPositionCache();
            System.out.println("WARNING: " + loadedChunks + " chunks loaded, cleaned position cache");
        }
    }
    
    public void clearPositionCacheIfLarge(int threshold) {
        if (chunkPositionCache.size() > threshold) {
            chunkPositionCache.clear();
        }
    }
    
    private void cleanupChunkPositionCache() {
        System.out.println("Cleaning chunk position cache (size: " + chunkPositionCache.size() + ")");
        
        Set<Long> loadedChunkKeys = new HashSet<>();
        for (World.ChunkPosition pos : chunks.keySet()) {
            long key = ((long) pos.getX() << 32) | (pos.getZ() & 0xFFFFFFFFL);
            loadedChunkKeys.add(key);
        }
        
        chunkPositionCache.entrySet().removeIf(entry -> !loadedChunkKeys.contains(entry.getKey()));
        
        System.out.println("Chunk position cache cleaned (new size: " + chunkPositionCache.size() + ")");
        MemoryProfiler.getInstance().takeSnapshot("after_chunk_position_cache_cleanup");
    }
    
    private Chunk safelyGenerateAndRegisterChunk(int chunkX, int chunkZ) {
        World.ChunkPosition position = getCachedChunkPosition(chunkX, chunkZ);
        
        if (chunks.containsKey(position)) {
            return chunks.get(position);
        }
        
        try {
            MemoryProfiler.getInstance().incrementAllocation("Chunk");
            
            Chunk newGeneratedChunk = terrainSystem.generateBareChunk(chunkX, chunkZ);
            
            if (newGeneratedChunk == null) {
                System.err.println("CRITICAL: generateChunk returned null for position (" + chunkX + ", " + chunkZ + ")");
                return null;
            }
            
            chunks.put(position, newGeneratedChunk);

            if (chunkLoadListener != null) {
                chunkLoadListener.accept(newGeneratedChunk);
            }

            if (shouldQueueForMesh(chunkX, chunkZ)) {
                meshPipeline.scheduleConditionalMeshBuild(newGeneratedChunk);
            }
            
            return newGeneratedChunk;
        } catch (Exception e) {
            System.err.println("Exception during chunk generation or registration at (" + chunkX + ", " + chunkZ + "): " + e.getMessage());
            System.err.println("Stack trace: " + Arrays.toString(e.getStackTrace()));
            chunks.remove(position);
            return null;
        }
    }
    
    private boolean shouldQueueForMesh(int chunkX, int chunkZ) {
        Player player = Game.getPlayer();
        if (player == null) return true;
        
        int playerChunkX = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        int distanceToPlayer = Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ));
        
        return distanceToPlayer <= config.getBorderChunkDistance();
    }
    
    /**
     * Get all chunks that are dirty and need to be saved.
     * @return List of dirty chunks
     */
    public List<Chunk> getDirtyChunks() {
        List<Chunk> dirtyChunks = new ArrayList<>();
        for (Chunk chunk : chunks.values()) {
            if (chunk != null && chunk.isDirty()) {
                dirtyChunks.add(chunk);
            }
        }
        return dirtyChunks;
    }

    public void cleanup() {
        for (Chunk chunk : new HashMap<>(chunks).values()) {
            chunk.cleanupGpuResources();
        }
        
        chunks.clear();
        chunkPositionCache.clear();
    }
}
