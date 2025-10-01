package com.stonebreak.world.memory;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.mesh.data.WorldChunkStore;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.chunk.ChunkManager;

import java.util.*;

public class WorldMemoryManager {
    private final WorldConfiguration config;
    private final WorldChunkStore chunkStore;
    
    public WorldMemoryManager(WorldConfiguration config, WorldChunkStore chunkStore) {
        this.config = config;
        this.chunkStore = chunkStore;
    }
    
    public void performMemoryManagement() {
        int loadedChunks = chunkStore.getLoadedChunkCount();
        
        if (loadedChunks > WorldConfiguration.EMERGENCY_CHUNK_THRESHOLD) {
            handleEmergencyMemoryPressure(loadedChunks);
        } else if (loadedChunks > WorldConfiguration.WARNING_CHUNK_THRESHOLD) {
            handleWarningMemoryPressure(loadedChunks);
        } else if (loadedChunks > WorldConfiguration.HIGH_CHUNK_THRESHOLD) {
            handleHighMemoryPressure(loadedChunks);
        }
    }
    
    private void handleEmergencyMemoryPressure(int loadedChunks) {
        if (loadedChunks > WorldConfiguration.EMERGENCY_CHUNK_THRESHOLD) {
            int unloadCount = loadedChunks > 800 ? 400 : 200;
            System.out.println("CRITICAL EMERGENCY: " + loadedChunks + " chunks loaded, triggering massive unloading");
            forceUnloadDistantChunks(unloadCount);
        }
    }
    
    private void handleWarningMemoryPressure(int loadedChunks) {
        System.out.println("EMERGENCY: " + loadedChunks + " chunks loaded, triggering emergency unloading");
        forceUnloadDistantChunks(200);
    }
    
    private void handleHighMemoryPressure(int loadedChunks) {
        chunkStore.cleanupPositionCacheIfNeeded(loadedChunks);
        
        if (loadedChunks % 50 == 0) {
            Game.forceGCAndReport("Proactive GC at " + loadedChunks + " chunks");
        }
    }
    
    public void forceUnloadDistantChunks(int maxUnloadCount) {
        Player player = Game.getPlayer();
        if (player == null) return;
        
        int playerChunkX = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        
        int emergencyDistance = maxUnloadCount > 200 ? config.getEmergencyUnloadDistance() : config.getWarningUnloadDistance();
        
        Set<World.ChunkPosition> chunksToUnload = findDistantChunks(playerChunkX, playerChunkZ, emergencyDistance);
        
        List<World.ChunkPosition> sortedUnloads = sortChunksByDistance(chunksToUnload, playerChunkX, playerChunkZ);
        
        int unloadedCount = unloadChunks(sortedUnloads, maxUnloadCount);
        
        if (unloadedCount > 0) {
            performPostUnloadCleanup(unloadedCount);
        }
    }
    
    private Set<World.ChunkPosition> findDistantChunks(int playerChunkX, int playerChunkZ, int emergencyDistance) {
        Set<World.ChunkPosition> chunksToUnload = new HashSet<>();
        
        for (World.ChunkPosition pos : chunkStore.getAllChunkPositions()) {
            int distance = Math.max(Math.abs(pos.getX() - playerChunkX), Math.abs(pos.getZ() - playerChunkZ));
            if (distance > emergencyDistance) {
                chunksToUnload.add(pos);
            }
        }
        
        return chunksToUnload;
    }
    
    private List<World.ChunkPosition> sortChunksByDistance(Set<World.ChunkPosition> chunks, int playerX, int playerZ) {
        List<World.ChunkPosition> sortedUnloads = new ArrayList<>(chunks);
        sortedUnloads.sort((a, b) -> {
            int distA = Math.max(Math.abs(a.getX() - playerX), Math.abs(a.getZ() - playerZ));
            int distB = Math.max(Math.abs(b.getX() - playerX), Math.abs(b.getZ() - playerZ));
            return Integer.compare(distB, distA); // Furthest first
        });
        return sortedUnloads;
    }
    
    private int unloadChunks(List<World.ChunkPosition> chunksToUnload, int maxUnloadCount) {
        int unloadedCount = 0;
        int protectedCount = 0;

        for (World.ChunkPosition pos : chunksToUnload) {
            // Check if chunk is dirty before unloading
            var chunk = chunkStore.getChunk(pos.getX(), pos.getZ());
            if (chunk != null && chunk.isDirty()) {
                // SAVE-THEN-UNLOAD: Save dirty chunk first, then unload it
                saveAndUnloadDirtyChunk(pos);
                protectedCount++; // Count as "protected" but actually handled
                unloadedCount++; // Count toward unload limit
            } else {
                // Normal unload for clean chunks
                try {
                    chunkStore.unloadChunk(pos.getX(), pos.getZ());
                    unloadedCount++;
                } catch (Exception e) {
                    System.err.println("Memory Manager: Failed to unload chunk (" + pos.getX() + ", " + pos.getZ() + "): " + e.getMessage());
                    // Continue with other chunks, don't let one failure stop memory management
                }
            }

            if (unloadedCount >= maxUnloadCount) break;
        }

        if (protectedCount > 0) {
            System.out.println("Memory Manager: Saved and unloaded " + protectedCount + " dirty chunks with player edits. Unloaded " + (unloadedCount - protectedCount) + " clean chunks.");
        }

        return unloadedCount;
    }

    /**
     * Saves a dirty chunk synchronously, then unloads it during memory pressure.
     * This ensures player edits are preserved while managing memory.
     */
    private void saveAndUnloadDirtyChunk(World.ChunkPosition pos) {
        try {
            // The chunkStore.unloadChunk() method already handles saving dirty chunks
            // via saveChunkOnUnload() before proceeding with unload
            chunkStore.unloadChunk(pos.getX(), pos.getZ());
            System.out.println("[MEMORY-SAVE-THEN-UNLOAD] Successfully saved and unloaded dirty chunk (" + pos.getX() + ", " + pos.getZ() + ")");
        } catch (Exception e) {
            System.err.println("Memory Manager: CRITICAL: Failed to save-then-unload dirty chunk (" + pos.getX() + ", " + pos.getZ() + "): " + e.getMessage());
            // Chunk remains in memory if save-then-unload failed
        }
    }
    
    private void performPostUnloadCleanup(int unloadedCount) {
        chunkStore.clearPositionCacheIfLarge(100);
        
        System.out.println("Emergency unloaded " + unloadedCount + " distant chunks. Total remaining: " + chunkStore.getLoadedChunkCount());
        Game.forceGCAndReport("After emergency chunk unloading");
    }
    
    public boolean isHighMemoryPressure() {
        return ChunkManager.isHighMemoryPressure();
    }
}