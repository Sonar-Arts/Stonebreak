package com.stonebreak.world.chunk.api.commonChunkOperations.data;

/**
 * Chunk lifecycle states for the Common Chunk Operations (CCO) API.
 * Defines all possible states a chunk can be in during its lifecycle.
 *
 * Thread-safe enum used with atomic state management.
 */
public enum CcoChunkState {
    /**
     * Empty/uninitialized state - chunk object exists but has no data.
     */
    EMPTY,

    /**
     * Chunk has been created but blocks not yet populated.
     */
    CREATED,

    /**
     * Block data has been generated/populated.
     */
    BLOCKS_POPULATED,

    /**
     * Features (trees, structures) have been populated.
     */
    FEATURES_POPULATED,

    /**
     * Block data has been modified and mesh needs regeneration.
     */
    MESH_DIRTY,

    /**
     * Mesh generation is currently scheduled or in progress.
     */
    MESH_GENERATING,

    /**
     * Mesh data has been generated on CPU and is ready for GPU upload.
     */
    MESH_CPU_READY,

    /**
     * Mesh has been uploaded to GPU and is ready for rendering.
     */
    MESH_GPU_UPLOADED,

    /**
     * Block data has been modified by player and needs to be saved.
     * This is SEPARATE from MESH_DIRTY which is for rendering only.
     */
    DATA_MODIFIED,

    /**
     * Chunk is being unloaded and should not be processed.
     */
    UNLOADING,

    /**
     * Chunk is fully ready for gameplay (all data populated, mesh uploaded).
     */
    READY,

    /**
     * Chunk is actively being used in gameplay.
     */
    ACTIVE,

    /**
     * Chunk has been fully unloaded and resources freed.
     */
    UNLOADED
}
