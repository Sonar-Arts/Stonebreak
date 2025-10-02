package com.stonebreak.world.chunk.api.commonChunkOperations.operations;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.function.Consumer;

/**
 * CCO Neighbor Coordinator - Manages chunk neighbor operations and state coordination.
 *
 * Part of the Common Chunk Operations API, this class handles:
 * - Neighbor detection when edge blocks change
 * - Mesh dirty marking for affected neighbors
 * - Neighbor readiness checks for rendering
 * - Border chunk existence management
 *
 * Design Philosophy:
 * - Uses CCO state management and dirty tracking exclusively
 * - Dependency injection pattern for chunk retrieval (ChunkProvider interface)
 * - Pure coordination logic - no mesh generation or rendering code
 * - Thread-safe operations through CCO atomic state management
 *
 * Performance:
 * - O(1) edge detection
 * - O(4) neighbor checks maximum (cardinal directions only)
 * - Lock-free CCO state queries
 *
 * @since CCO 1.0
 */
public class CcoNeighborCoordinator {

    /**
     * Interface for retrieving chunks from the world storage system.
     * Allows dependency inversion - coordinator doesn't depend on concrete storage.
     */
    public interface ChunkProvider {
        /**
         * Gets a chunk at the specified coordinates.
         *
         * @param chunkX Chunk X coordinate
         * @param chunkZ Chunk Z coordinate
         * @return Chunk instance, or null if not loaded
         */
        Chunk getChunk(int chunkX, int chunkZ);

        /**
         * Ensures a chunk exists at the specified coordinates.
         * May load from disk or generate if needed.
         *
         * @param chunkX Chunk X coordinate
         * @param chunkZ Chunk Z coordinate
         */
        void ensureChunkExists(int chunkX, int chunkZ);
    }

    private final ChunkProvider chunkProvider;
    private final WorldConfiguration config;

    /**
     * Creates a neighbor coordinator with dependency injection.
     *
     * @param chunkProvider Provider for chunk retrieval operations
     * @param config World configuration for chunk sizes and distances
     */
    public CcoNeighborCoordinator(ChunkProvider chunkProvider, WorldConfiguration config) {
        if (chunkProvider == null) {
            throw new IllegalArgumentException("ChunkProvider cannot be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("WorldConfiguration cannot be null");
        }
        this.chunkProvider = chunkProvider;
        this.config = config;
    }

    // ========== Public API ==========

    /**
     * Marks neighbor chunks dirty when an edge block changes.
     * Only affects neighbors if the block is on a chunk boundary (x/z == 0 or CHUNK_SIZE-1).
     *
     * @param chunkX Chunk X coordinate where block changed
     * @param chunkZ Chunk Z coordinate where block changed
     * @param localX Local X coordinate within chunk (0-15)
     * @param localZ Local Z coordinate within chunk (0-15)
     */
    public void markNeighborsDirtyOnEdgeChange(int chunkX, int chunkZ, int localX, int localZ) {
        // Check each edge and mark corresponding neighbor
        if (localX == 0) {
            markNeighborMeshDirty(chunkX - 1, chunkZ);
        }
        if (localX == WorldConfiguration.CHUNK_SIZE - 1) {
            markNeighborMeshDirty(chunkX + 1, chunkZ);
        }
        if (localZ == 0) {
            markNeighborMeshDirty(chunkX, chunkZ - 1);
        }
        if (localZ == WorldConfiguration.CHUNK_SIZE - 1) {
            markNeighborMeshDirty(chunkX, chunkZ + 1);
        }
    }

    /**
     * Marks neighbor chunks dirty and schedules mesh rebuilds when an edge block changes.
     * This is the preferred method when mesh rebuilding should happen immediately.
     *
     * @param chunkX Chunk X coordinate where block changed
     * @param chunkZ Chunk Z coordinate where block changed
     * @param localX Local X coordinate within chunk (0-15)
     * @param localZ Local Z coordinate within chunk (0-15)
     * @param meshBuildScheduler Callback to schedule mesh builds
     */
    public void markAndScheduleNeighbors(int chunkX, int chunkZ, int localX, int localZ,
                                         Consumer<Chunk> meshBuildScheduler) {
        if (meshBuildScheduler == null) {
            throw new IllegalArgumentException("Mesh build scheduler cannot be null");
        }

        // Check each edge and mark + schedule corresponding neighbor
        if (localX == 0) {
            markAndScheduleNeighbor(chunkX - 1, chunkZ, meshBuildScheduler);
        }
        if (localX == WorldConfiguration.CHUNK_SIZE - 1) {
            markAndScheduleNeighbor(chunkX + 1, chunkZ, meshBuildScheduler);
        }
        if (localZ == 0) {
            markAndScheduleNeighbor(chunkX, chunkZ - 1, meshBuildScheduler);
        }
        if (localZ == WorldConfiguration.CHUNK_SIZE - 1) {
            markAndScheduleNeighbor(chunkX, chunkZ + 1, meshBuildScheduler);
        }
    }

    /**
     * Ensures all cardinal neighbors (N, S, E, W) are ready for rendering.
     * Schedules mesh builds for any neighbors that are populated but don't have meshes.
     *
     * @param centerChunkX Center chunk X coordinate
     * @param centerChunkZ Center chunk Z coordinate
     * @param meshBuildScheduler Callback to schedule mesh builds
     */
    public void ensureNeighborsReadyForRender(int centerChunkX, int centerChunkZ,
                                               Consumer<Chunk> meshBuildScheduler) {
        if (meshBuildScheduler == null) {
            throw new IllegalArgumentException("Mesh build scheduler cannot be null");
        }

        // Check all 4 cardinal neighbors
        int[][] neighborOffsets = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

        for (int[] offset : neighborOffsets) {
            int neighborX = centerChunkX + offset[0];
            int neighborZ = centerChunkZ + offset[1];

            Chunk neighbor = chunkProvider.getChunk(neighborX, neighborZ);

            if (neighbor != null && isChunkPopulated(neighbor)) {
                if (!isChunkMeshReady(neighbor)) {
                    meshBuildScheduler.accept(neighbor);
                }
            }
        }
    }

    /**
     * Ensures border chunks exist around the player for smooth chunk loading.
     * Border chunks are outside the render distance but needed for edge mesh generation.
     *
     * @param playerChunkX Player's current chunk X coordinate
     * @param playerChunkZ Player's current chunk Z coordinate
     */
    public void ensureBorderChunksExist(int playerChunkX, int playerChunkZ) {
        int borderDistance = config.getBorderChunkDistance();
        int renderDistance = config.getRenderDistance();

        for (int x = playerChunkX - borderDistance; x <= playerChunkX + borderDistance; x++) {
            for (int z = playerChunkZ - borderDistance; z <= playerChunkZ + borderDistance; z++) {
                // Only ensure chunks that are outside render distance (true border chunks)
                boolean isInsideRenderDist = (x >= playerChunkX - renderDistance &&
                                             x <= playerChunkX + renderDistance &&
                                             z >= playerChunkZ - renderDistance &&
                                             z <= playerChunkZ + renderDistance);
                if (!isInsideRenderDist) {
                    chunkProvider.ensureChunkExists(x, z);
                }
            }
        }
    }

    // ========== Utility Methods ==========

    /**
     * Checks if a block position is on a chunk edge.
     *
     * @param localX Local X coordinate (0-15)
     * @param localZ Local Z coordinate (0-15)
     * @return true if on any edge
     */
    public static boolean isEdgeBlock(int localX, int localZ) {
        return localX == 0 || localX == WorldConfiguration.CHUNK_SIZE - 1 ||
               localZ == 0 || localZ == WorldConfiguration.CHUNK_SIZE - 1;
    }

    /**
     * Checks if a block position is on a specific edge.
     *
     * @param localX Local X coordinate (0-15)
     * @param localZ Local Z coordinate (0-15)
     * @param direction Direction to check: 0=North, 1=South, 2=East, 3=West
     * @return true if on specified edge
     */
    public static boolean isOnEdge(int localX, int localZ, int direction) {
        return switch (direction) {
            case 0 -> localZ == 0; // North
            case 1 -> localZ == WorldConfiguration.CHUNK_SIZE - 1; // South
            case 2 -> localX == WorldConfiguration.CHUNK_SIZE - 1; // East
            case 3 -> localX == 0; // West
            default -> false;
        };
    }

    // ========== Private Helper Methods ==========

    /**
     * Marks a neighbor chunk's mesh as dirty using CCO dirty tracker.
     */
    private void markNeighborMeshDirty(int chunkX, int chunkZ) {
        Chunk neighbor = chunkProvider.getChunk(chunkX, chunkZ);
        if (neighbor != null) {
            neighbor.getCcoDirtyTracker().markMeshDirtyOnly();
        }
    }

    /**
     * Marks a neighbor chunk's mesh as dirty and schedules rebuild.
     */
    private void markAndScheduleNeighbor(int chunkX, int chunkZ, Consumer<Chunk> meshBuildScheduler) {
        Chunk neighbor = chunkProvider.getChunk(chunkX, chunkZ);
        if (neighbor != null) {
            neighbor.getCcoDirtyTracker().markMeshDirtyOnly();
            meshBuildScheduler.accept(neighbor);
        }
    }

    /**
     * Checks if chunk is populated using CCO state.
     * A populated chunk has its blocks and features generated.
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
}
