package com.stonebreak.world;

import java.util.function.Consumer;
import java.util.function.Function;

import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.commonChunkOperations.operations.CcoNeighborCoordinator;
import com.stonebreak.world.chunk.api.mightyMesh.mmsCore.MmsMeshPipeline;
import com.stonebreak.world.chunk.utils.WorldChunkStore;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Owns chunk mesh scheduling for a {@link World}: dirty-flag marking via the CCO dirty
 * tracker, neighbor seam rebuilds, priority selection for player edits, and the per-tick
 * pumping of the {@link MmsMeshPipeline} build / GL-upload / GPU-cleanup queues.
 *
 * <p>Every entry point is a no-op when the world has no mesh pipeline (headless server
 * world, test mode), so callers never need their own {@code meshPipeline == null} guards.
 */
final class ChunkMeshScheduler {
    private final MmsMeshPipeline meshPipeline;
    private final CcoNeighborCoordinator neighborCoordinator;
    private final WorldChunkStore chunkStore;

    ChunkMeshScheduler(MmsMeshPipeline meshPipeline,
                       CcoNeighborCoordinator neighborCoordinator,
                       WorldChunkStore chunkStore) {
        this.meshPipeline = meshPipeline;
        this.neighborCoordinator = neighborCoordinator;
        this.chunkStore = chunkStore;
    }

    /** True when this world renders (has a mesh pipeline). */
    boolean hasPipeline() {
        return meshPipeline != null;
    }

    // ===== Per-tick pumping =====

    void requeueFailedChunks() {
        meshPipeline.requeueFailedChunks();
    }

    void processChunkMeshBuildRequests(World world) {
        meshPipeline.processChunkMeshBuildRequests(world);
    }

    void applyPendingGLUpdates() {
        if (meshPipeline == null) return; // Test mode - skip rendering updates
        meshPipeline.applyPendingGLUpdates();
    }

    void processGpuCleanupQueue() {
        if (meshPipeline == null) return; // Test mode - skip rendering updates
        meshPipeline.processGpuCleanupQueue();
    }

    int getPendingMeshBuildCount() {
        return meshPipeline != null ? meshPipeline.getPendingMeshBuildCount() : 0;
    }

    int getPendingGLUploadCount() {
        return meshPipeline != null ? meshPipeline.getPendingGLUploadCount() : 0;
    }

    /** Shuts the pipeline's worker threads down (no-op without a pipeline). */
    void shutdown() {
        if (meshPipeline != null) {
            meshPipeline.shutdown();
        }
    }

    /**
     * Queues the final main-thread GPU cleanup drain after the chunk store has been torn
     * down (nothing ticks this pipeline's queue once the world is swapped out).
     */
    void deferFinalGpuCleanup() {
        if (meshPipeline != null) {
            final MmsMeshPipeline mp = meshPipeline;
            com.stonebreak.core.Game.getInstance().runOnMainThread(mp::processGpuCleanupQueue);
        }
    }

    // ===== Scheduling =====

    /**
     * Ensures the chunk at the given position (and its neighbors) has a mesh build scheduled.
     * {@code chunkLoader} is the generating accessor used when the chunk is not yet resident.
     */
    void ensureChunkIsReadyForRender(int cx, int cz, Function<int[], Chunk> chunkLoader) {
        if (meshPipeline == null || neighborCoordinator == null) return; // Test mode - no rendering

        Chunk chunk = chunkStore.getChunk(cx, cz);

        if (chunk == null) {
            chunk = chunkLoader.apply(new int[] {cx, cz});
            if (chunk == null) {
                return;
            }
        }

        // Features are now always populated during chunk generation - no need to check

        boolean isMeshReady = chunk.isMeshGenerated() && chunk.isDataReadyForGL();
        boolean isMeshGenerating = chunk.isMeshDataGenerationScheduledOrInProgress();

        // CRITICAL FIX: If chunk has features but no mesh and isn't generating, force retry
        // This handles cases where mesh generation silently failed or was never attempted
        if (chunk.areFeaturesPopulated() && !isMeshReady && !isMeshGenerating) {
            // Force reset mesh state to allow retry
            resetMeshGenerationState(chunk);
            meshPipeline.scheduleConditionalMeshBuild(chunk);
        } else if (!isMeshReady) {
            meshPipeline.scheduleConditionalMeshBuild(chunk);
        }

        neighborCoordinator.ensureNeighborsReadyForRender(cx, cz, meshPipeline::scheduleConditionalMeshBuild);
    }

    /**
     * Schedules the rebuilds a block change at {@code (localX, localZ)} of {@code chunk}
     * requires: the chunk itself plus any seam neighbors. Player modifications use the
     * high-priority lanes for 1-frame feedback. No-op without rendering infrastructure.
     */
    void onBlockChanged(Chunk chunk, int chunkX, int chunkZ, int localX, int localZ, boolean isPlayerModification) {
        if (meshPipeline != null && neighborCoordinator != null) {
            if (isPlayerModification) {
                // PRIORITY PATH: Player modification - high priority async mesh generation
                // Uses PRIORITY_PLAYER_MODIFICATION to bypass batch limits for 1-frame feedback
                markChunkForMeshRebuildWithScheduling(chunk,
                    c -> meshPipeline.scheduleConditionalMeshBuild(c, MmsMeshPipeline.PRIORITY_PLAYER_MODIFICATION));
                neighborCoordinator.markAndScheduleNeighbors(chunkX, chunkZ, localX, localZ,
                    c -> meshPipeline.scheduleConditionalMeshBuild(c, MmsMeshPipeline.PRIORITY_NEIGHBOR_CHUNK));
            } else {
                // NORMAL PATH: World gen/loading - standard priority async mesh generation
                markChunkForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
                neighborCoordinator.markAndScheduleNeighbors(chunkX, chunkZ, localX, localZ, meshPipeline::scheduleConditionalMeshBuild);
            }
        }
    }

    /**
     * Schedules the rebuilds after a network chunk payload has been installed into
     * {@code chunk}: the chunk itself plus ALL four resident neighbors.
     */
    void onNetworkChunkInstalled(Chunk chunk, int chunkX, int chunkZ) {
        if (meshPipeline != null) {
            markChunkForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
            if (neighborCoordinator != null) {
                // Re-mesh ALL four resident neighbors: any neighbor meshed before this
                // payload landed built its border against an absent or empty chunk
                // (culled/sentinel faces, or all-AIR reads → spurious water sheets).
                // Streaming order is not guaranteed to be west/north-first — movement
                // west or north delivers new chunks on the far side of already-meshed
                // ones — so both edge pairs must be marked. Non-resident neighbors
                // no-op, and the dirty-flag gating keeps this to one rebuild each.
                neighborCoordinator.markAndScheduleNeighbors(chunkX, chunkZ, 0, 0,
                        meshPipeline::scheduleConditionalMeshBuild);
                neighborCoordinator.markAndScheduleNeighbors(chunkX, chunkZ,
                        WorldConfiguration.CHUNK_SIZE - 1, WorldConfiguration.CHUNK_SIZE - 1,
                        meshPipeline::scheduleConditionalMeshBuild);
            }
        }
    }

    /**
     * Marks the four seam neighbors of a freshly loaded chunk dirty so their border faces
     * recompute against the new data. No-op without a mesh pipeline.
     */
    void onChunkLoaded(int cx, int cz) {
        if (meshPipeline != null) {
            markMeshedNeighborDirty(cx - 1, cz);
            markMeshedNeighborDirty(cx + 1, cz);
            markMeshedNeighborDirty(cx, cz - 1);
            markMeshedNeighborDirty(cx, cz + 1);
        }
    }

    /** Standard-priority rebuild of a resident chunk (visual change without block change). */
    void scheduleRebuild(Chunk chunk) {
        markChunkForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
    }

    /**
     * Standard-priority rebuild of the chunk containing the given world coordinates.
     * No-op without a mesh pipeline or when the chunk isn't loaded.
     */
    void triggerChunkRebuild(int worldX, int worldZ) {
        if (meshPipeline == null) return; // Test mode - no rendering

        int chunkX = Math.floorDiv(worldX, WorldConfiguration.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(worldZ, WorldConfiguration.CHUNK_SIZE);

        Chunk chunk = chunkStore.getChunk(chunkX, chunkZ);
        if (chunk != null) {
            markChunkForMeshRebuildWithScheduling(chunk, meshPipeline::scheduleConditionalMeshBuild);
        }
    }

    /**
     * Player-priority re-mesh of a resident chunk whose rendered model variant changed
     * (block state flip). No-op without a mesh pipeline.
     */
    void scheduleChunkRemesh(Chunk chunk) {
        if (meshPipeline == null) return;
        chunk.getCcoDirtyTracker().markMeshDirtyOnly();
        meshPipeline.scheduleConditionalMeshBuild(chunk, MmsMeshPipeline.PRIORITY_PLAYER_MODIFICATION);
    }

    // ===== Dirty-tracker helpers =====

    /**
     * Marks a chunk for mesh rebuild using CCO dirty tracker.
     */
    private void markChunkForMeshRebuild(Chunk chunk) {
        chunk.getCcoDirtyTracker().markMeshDirtyOnly();
    }

    /**
     * Marks a chunk for mesh rebuild and schedules it using CCO dirty tracker.
     */
    private void markChunkForMeshRebuildWithScheduling(Chunk chunk, Consumer<Chunk> meshBuildScheduler) {
        markChunkForMeshRebuild(chunk);
        meshBuildScheduler.accept(chunk);
    }

    /**
     * Resets mesh generation state using CCO dirty tracker.
     */
    private void resetMeshGenerationState(Chunk chunk) {
        chunk.getCcoDirtyTracker().markMeshDirtyOnly();
    }

    /**
     * Marks an existing neighbor chunk dirty and schedules a rebuild so its
     * border faces (water seams, sentinel-culled boundaries) recompute with
     * correct data once this chunk is available.
     *
     * <p>Do NOT skip when the neighbor hasn't finished its first mesh build:
     * if the neighbor was scheduled before this chunk loaded, its in-flight
     * build is racing against the sentinel-opaque path in MmsFaceCullingService
     * and may have already baked culled boundary faces. Dropping the signal
     * here leaves those faces missing until the player edits the chunk. The
     * mesh pipeline coalesces duplicate schedule requests, so re-scheduling a
     * pending build is cheap.
     */
    private void markMeshedNeighborDirty(int chunkX, int chunkZ) {
        Chunk neighbor = chunkStore.getChunk(chunkX, chunkZ);
        if (neighbor == null) return;
        neighbor.getCcoDirtyTracker().markMeshDirtyOnly();
        meshPipeline.scheduleConditionalMeshBuild(neighbor);
    }
}
