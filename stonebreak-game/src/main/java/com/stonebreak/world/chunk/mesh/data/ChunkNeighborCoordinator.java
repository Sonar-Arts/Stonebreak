package com.stonebreak.world.chunk.mesh.data;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.ChunkStateManager;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.function.Consumer;

public class ChunkNeighborCoordinator {
    private final WorldChunkStore chunkStore;
    private final ChunkStateManager stateManager;
    private final WorldConfiguration config;
    
    public ChunkNeighborCoordinator(WorldChunkStore chunkStore, ChunkStateManager stateManager, WorldConfiguration config) {
        this.chunkStore = chunkStore;
        this.stateManager = stateManager;
        this.config = config;
    }
    
    public void rebuildNeighborChunks(int chunkX, int chunkZ, int localX, int localZ) {
        if (localX == 0) {
            rebuildNeighborChunk(chunkX - 1, chunkZ);
        }
        if (localX == WorldConfiguration.CHUNK_SIZE - 1) {
            rebuildNeighborChunk(chunkX + 1, chunkZ);
        }
        if (localZ == 0) {
            rebuildNeighborChunk(chunkX, chunkZ - 1);
        }
        if (localZ == WorldConfiguration.CHUNK_SIZE - 1) {
            rebuildNeighborChunk(chunkX, chunkZ + 1);
        }
    }
    
    public void ensureNeighborsReadyForRender(int centerChunkX, int centerChunkZ, Consumer<Chunk> meshBuildScheduler) {
        int[][] neighborOffsets = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
        
        for (int[] offset : neighborOffsets) {
            int neighborX = centerChunkX + offset[0];
            int neighborZ = centerChunkZ + offset[1];
            
            Chunk neighbor = chunkStore.getChunk(neighborX, neighborZ);
            
            if (neighbor != null && neighbor.areFeaturesPopulated()) {
                boolean isNeighborMeshReady = neighbor.isMeshGenerated() && neighbor.isDataReadyForGL();
                if (!isNeighborMeshReady) {
                    meshBuildScheduler.accept(neighbor);
                }
            }
        }
    }
    
    public void ensureBorderChunksExist(int playerChunkX, int playerChunkZ) {
        int borderDistance = config.getBorderChunkDistance();
        int renderDistance = config.getRenderDistance();
        
        for (int x = playerChunkX - borderDistance; x <= playerChunkX + borderDistance; x++) {
            for (int z = playerChunkZ - borderDistance; z <= playerChunkZ + borderDistance; z++) {
                boolean isInsideRenderDist = (x >= playerChunkX - renderDistance && 
                                            x <= playerChunkX + renderDistance &&
                                            z >= playerChunkZ - renderDistance && 
                                            z <= playerChunkZ + renderDistance);
                if (!isInsideRenderDist) {
                    chunkStore.ensureChunkExists(x, z);
                }
            }
        }
    }
    
    private void rebuildNeighborChunk(int chunkX, int chunkZ) {
        Chunk neighbor = chunkStore.getChunk(chunkX, chunkZ);
        if (neighbor != null) {
            stateManager.markForMeshRebuild(neighbor);
        }
    }
}