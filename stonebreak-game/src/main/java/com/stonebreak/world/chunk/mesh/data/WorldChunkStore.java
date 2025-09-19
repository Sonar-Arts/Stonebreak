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

public class WorldChunkStore {
    private final Map<World.ChunkPosition, Chunk> chunks;
    private final Map<Long, World.ChunkPosition> chunkPositionCache;
    private final TerrainGenerationSystem terrainSystem;
    private final WorldConfiguration config;
    private final ChunkMeshBuildingPipeline meshPipeline;
    
    public WorldChunkStore(TerrainGenerationSystem terrainSystem, WorldConfiguration config, ChunkMeshBuildingPipeline meshPipeline) {
        this.chunks = new ConcurrentHashMap<>();
        this.chunkPositionCache = new ConcurrentHashMap<>();
        this.terrainSystem = terrainSystem;
        this.config = config;
        this.meshPipeline = meshPipeline;
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
            // Generate chunk immediately to avoid blocking
            chunk = safelyGenerateAndRegisterChunk(x, z);

            // Try to load from save system asynchronously and replace if found
            try {
                com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
                var saveSystem = (game != null) ? game.getWorldSaveSystem() : null;

                if (saveSystem != null && saveSystem.isInitialized()) {
                    // Capture the final chunk reference for async operations
                    final Chunk generatedChunk = chunk;

                    // Asynchronously check if saved chunk exists and load it
                    saveSystem.chunkExists(x, z)
                        .thenAccept(exists -> {
                            if (exists != null && exists) {
                                // Load the saved chunk asynchronously
                                saveSystem.loadChunk(x, z)
                                    .thenAccept(loaded -> {
                                        if (loaded != null) {
                                            // Replace generated chunk with loaded chunk
                                            chunks.put(position, loaded);
                                            System.out.println("[CHUNK-LOAD] Replaced generated chunk (" + x + ", " + z + ") with saved chunk");

                                            // Ensure mesh is scheduled for build for loaded chunks
                                            if (shouldQueueForMesh(x, z)) {
                                                meshPipeline.scheduleConditionalMeshBuild(loaded);
                                            }
                                        }
                                    })
                                    .exceptionally(throwable -> {
                                        System.err.println("Error loading chunk (" + x + ", " + z + ") from storage: " + throwable.getMessage());
                                        return null;
                                    });
                            }
                        })
                        .exceptionally(throwable -> {
                            System.err.println("Error checking chunk existence (" + x + ", " + z + "): " + throwable.getMessage());
                            return null;
                        });
                }
            } catch (Exception e) {
                // Non-fatal: generation already succeeded, just log the error
                System.err.println("Error initiating async chunk load (" + x + ", " + z + "): " + e.getMessage());
            }
        }
        
        return chunk;
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
