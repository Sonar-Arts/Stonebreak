package com.stonebreak.world.chunk.mesh.data;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.function.Consumer;

/**
 * Coordinates neighbor chunk operations using CCO API.
 * Handles edge block changes and neighbor mesh rebuilds without direct WorldChunkStore access.
 */
public class ChunkNeighborCoordinator {
    private final WorldChunkStore chunkStore;
    private final WorldConfiguration config;

    public ChunkNeighborCoordinator(WorldChunkStore chunkStore, WorldConfiguration config) {
        this.chunkStore = chunkStore;
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

    /**
     * Marks neighbor chunks dirty and schedules their mesh rebuilds when an edge block changes.
     */
    public void rebuildNeighborChunksScheduled(int chunkX, int chunkZ, int localX, int localZ, Consumer<Chunk> meshBuildScheduler) {
        if (localX == 0) {
            rebuildNeighborChunkScheduled(chunkX - 1, chunkZ, meshBuildScheduler);
        }
        if (localX == WorldConfiguration.CHUNK_SIZE - 1) {
            rebuildNeighborChunkScheduled(chunkX + 1, chunkZ, meshBuildScheduler);
        }
        if (localZ == 0) {
            rebuildNeighborChunkScheduled(chunkX, chunkZ - 1, meshBuildScheduler);
        }
        if (localZ == WorldConfiguration.CHUNK_SIZE - 1) {
            rebuildNeighborChunkScheduled(chunkX, chunkZ + 1, meshBuildScheduler);
        }
    }
    
    public void ensureNeighborsReadyForRender(int centerChunkX, int centerChunkZ, Consumer<Chunk> meshBuildScheduler) {
        int[][] neighborOffsets = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

        for (int[] offset : neighborOffsets) {
            int neighborX = centerChunkX + offset[0];
            int neighborZ = centerChunkZ + offset[1];

            Chunk neighbor = chunkStore.getChunk(neighborX, neighborZ);

            if (neighbor != null && isChunkPopulated(neighbor)) {
                if (!isChunkMeshReady(neighbor)) {
                    meshBuildScheduler.accept(neighbor);
                }
            }
        }
    }

    /**
     * Checks if chunk is populated using CCO state.
     */
    private boolean isChunkPopulated(Chunk chunk) {
        return chunk.getCcoStateManager().hasAnyState(
            CcoChunkState.BLOCKS_POPULATED,
            CcoChunkState.FEATURES_POPULATED,
            CcoChunkState.READY,
            CcoChunkState.ACTIVE
        );
    }

    /**
     * Checks if chunk mesh is ready for rendering using CCO state.
     */
    private boolean isChunkMeshReady(Chunk chunk) {
        return chunk.getCcoStateManager().isRenderable();
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
            markChunkForMeshRebuild(neighbor);
        }
    }

    private void rebuildNeighborChunkScheduled(int chunkX, int chunkZ, Consumer<Chunk> meshBuildScheduler) {
        Chunk neighbor = chunkStore.getChunk(chunkX, chunkZ);
        if (neighbor != null) {
            markChunkForMeshRebuild(neighbor);
            meshBuildScheduler.accept(neighbor);
        }
    }

    /**
     * Marks a chunk for mesh rebuild using CCO state management.
     */
    private void markChunkForMeshRebuild(Chunk chunk) {
        synchronized (chunk) {
            chunk.getCcoStateManager().addState(CcoChunkState.MESH_DIRTY);
        }
    }
}
